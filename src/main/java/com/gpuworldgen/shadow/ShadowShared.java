package com.gpuworldgen.shadow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真实 PerlinNoise 实例数据共享层（影子采样 + v0.11.0 GPU 接管）。
 *
 * <h3>v0.10.x 的问题（两轮真机日志实证）</h3>
 * 旧版把「噪声查询坐标直接 floor 成整数柱号」当缓存键，预生成 16x16x384 的整数柱补丁。
 * 但 MC 的 3D 噪声是按<b>格点</b>采样的：坐标 = 方块坐标 × xzScale/yScale（例如 cavity 为
 * 1.0/0.6667、base noise 为 0.25/0.125），y 方向常常不是整数、也不是 1 的步长；而且
 * 区块初次生成是「一次性爆发」（日志：8 秒内 83 万次调用），异步补丁预生成永远追不上。
 * 结果：命中率 0.0%，每区块还白烧 ~10ms 渲染线程。
 *
 * <h3>v0.11.0 做法</h3>
 * <ol>
 *   <li><b>格点学习</b>：每个被捕获的 PerlinNoise 实例，从最初几百次真实查询里自动推断出
 *       采样格点 (起点, 步长, 层数)：x = bx + qx*dx，y = by + qy*dy。只认「严格落在格点上」的
 *       查询（{@code |(x-bx)/dx - round(...)| <= tol}），否则一律 CPU fallback —— 宁可不服务，
 *       也绝不返回错值。</li>
 *   <li><b>区域批量预取</b>：一次 draw 算一整列（nY 层），一次回读取回整批；矩形区域按锚点
 *       （最近一次 miss 的格点坐标）扩展，显存/时长都有上限。</li>
 *   <li><b>抽检门禁</b>：每个批次回读后与 CPU 的 MC 精确参考逐柱抽检（<1e-5），只有通过的柱
 *       才写入缓存，未通过 = 永久 CPU fallback。</li>
 * </ol>
 *
 * 线程安全：缓存 ConcurrentHashMap（写=渲染线程，读=mixin 的世界生成线程）；
 * 学习阶段用 synchronized 保护；统计字段用普通 long（允许近似，避免热路径原子竞争）。
 */
public final class ShadowShared {

    // ======================= 2D 影子采样（沿用 v0.8.0） =======================

    /** 首个真实实例数据是否已就绪（2D 影子对拍用） */
    public static volatile boolean ready = false;
    /** 接管已激活（至少一个实例可服务） */
    public static volatile boolean takeoverActive = false;
    public static volatile byte[] realPerms = new byte[256];
    public static volatile byte[] realPermsRef;
    public static volatile double realOx, realOy, realOz;

    /** v0.10.0 统计（v0.11.0 起为各实例合计） */
    public static final AtomicLong gpuHits = new AtomicLong();
    public static final AtomicLong gpuMisses = new AtomicLong();
    /** 落在格点之外（分数坐标 / 混合尺度）被拒绝服务的次数 */
    public static final AtomicLong offLattice = new AtomicLong();

    private static final int CAP = 256;
    private static final double[] xs = new double[CAP];
    private static final double[] zs = new double[CAP];
    private static int head = 0;
    private static int count = 0;

    /** mixin 首次调用：填充 2D 影子对拍用的真实实例数据（只填一次）。 */
    public static synchronized void supplyRealData(byte[] perms, double ox, double oy, double oz) {
        if (ready) return;
        if (perms == null || perms.length != 256) return;
        realPermsRef = perms;
        System.arraycopy(perms, 0, realPerms, 0, 256);
        realOx = ox;
        realOy = oy;
        realOz = oz;
        ready = true;
        System.out.println("[GPUWorldGen] 影子模式：已捕获真实 PerlinNoise 实例 "
                + "offsetX=" + ox + " offsetY=" + oy + " offsetZ=" + oz);
    }

    /** mixin 节流调用：收集一个采样坐标（覆盖式环形队列）。 */
    public static void offer(double x, double z) {
        if (!ready) return;
        synchronized (ShadowShared.class) {
            xs[head] = x;
            zs[head] = z;
            head = (head + 1) % CAP;
            if (count < CAP) count++;
        }
    }

    /** 渲染线程取走最多 n 个 2D 坐标（FIFO）。返回 null 表示暂无数据。 */
    public static synchronized double[][] drain(int n) {
        if (count == 0) return null;
        int take = Math.min(n, count);
        double[][] out = new double[take][2];
        int idx = (head - count + CAP) % CAP;
        for (int i = 0; i < take; i++) {
            out[i][0] = xs[idx];
            out[i][1] = zs[idx];
            idx = (idx + 1) % CAP;
        }
        count -= take;
        return out;
    }

    // ======================= v0.11.0 实例注册表 =======================

    /** 最多注册多少个 PerlinNoise 实例（大整合包里 3D 噪声实例可达几十个） */
    private static final int MAX_REG = 64;
    private static final Instance[] REG = new Instance[MAX_REG + 1];
    private static int regCount = 0;
    private static int servedSlots = 0;
    private static final Object LOCK = new Object();

    /** 每个真实 PerlinNoise 实例一份：格点元数据 + 柱缓存 + 预取区域状态。 */
    public static final class Instance {

        public final int id;
        /** 强引用，防止实例被回收后 id 复用（同时也是身份标识，mixin 用它做 O(1) 判断） */
        public final Object ref;
        public final byte[] perms;
        public final double ox, oy, oz;

        // ---------- 学习阶段 ----------
        private static final int SAMPLE_MAX = 64;
        private static final long MIN_CALLS = 600;
        private final double[] svX = new double[SAMPLE_MAX];
        private final double[] svY = new double[SAMPLE_MAX];
        private final double[] svZ = new double[SAMPLE_MAX];
        private int nX, nY, nZ;
        private double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        private double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        private double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        private long obsCalls;

        /** 学习完成（无论成功失败，都只做一次） */
        public volatile boolean learned;
        /** 是否可服务（格点严格 + 拿到槽位） */
        public volatile boolean ok;
        public volatile String learnNote = "";

        // ---------- 格点元数据（learned 后只读） ----------
        public volatile double bx, by, bz;      // 格点起点（观测到的最小坐标）
        public volatile double dx = 1, dy = 1, dz = 1;   // 步长
        public volatile double ix = 1, iy = 1, iz = 1;   // 1/步长
        public volatile int layers = 1;         // y 层数
        /** 索引空间 → 键 打包用 */
        private volatile double tol = 1e-6;

        // ---------- 柱缓存 ----------
        public final ConcurrentHashMap<Long, float[]> cols = new ConcurrentHashMap<>();
        public volatile long hits, misses, oob, offGrid;

        // ---------- 预取区域（索引空间矩形） ----------
        public volatile boolean regionActive;
        public volatile int rQx0, rQz0, rW, rH, rRow, rCol;
        public volatile long passes, columns, failPasses;
        /** 连续整批抽检失败次数（连续 3 次才永久降级） */
        public int failStreak;
        public volatile int anchorQx, anchorQz;
        public volatile boolean anchorValid;
        private boolean slotHeld;
        public volatile int lastRegionCx = Integer.MIN_VALUE, lastRegionCz = Integer.MIN_VALUE;

        Instance(int id, Object ref, byte[] perms, double ox, double oy, double oz) {
            this.id = id;
            this.ref = ref;
            this.perms = perms;
            this.ox = ox; this.oy = oy; this.oz = oz;
        }

        // ---------------- 热路径：mixin 每个 3D 查询调用 ----------------

        /**
         * @return 命中的 GPU 噪声值；NaN = 未命中（调用方走 CPU 原方法）
         */
        public float offer(double x, double y, double z) {
            if (!learned) {
                observe(x, y, z);
                misses++;
                return Float.NaN;
            }
            if (!ok) {
                misses++;
                return Float.NaN;
            }
            float v = lookup(x, y, z);
            if (v == v) {                 // NaN 自比较为 false
                hits++;
                return v;
            }
            misses++;
            return Float.NaN;
        }

        /** 精确格点查找：坐标必须严格落在学习到的格点上，否则 NaN。 */
        private float lookup(double x, double y, double z) {
            double tx = (x - bx) * ix;
            long qx = Math.round(tx);
            if (Math.abs(tx - (double) qx) > tol || qx < Integer.MIN_VALUE || qx > Integer.MAX_VALUE) {
                offGrid++;
                ShadowShared.offLattice.incrementAndGet();
                return Float.NaN;
            }
            double tz = (z - bz) * iz;
            long qz = Math.round(tz);
            if (Math.abs(tz - (double) qz) > tol || qz < Integer.MIN_VALUE || qz > Integer.MAX_VALUE) {
                offGrid++;
                ShadowShared.offLattice.incrementAndGet();
                return Float.NaN;
            }
            double ty = (y - by) * iy;
            long qy = Math.round(ty);
            int n = layers;
            if (Math.abs(ty - (double) qy) > tol || qy < 0 || qy >= n) {
                oob++;
                return Float.NaN;
            }
            float[] col = cols.get(ShadowShared.keyOf((int) qx, (int) qz));
            if (col == null) {
                noteAnchor((int) qx, (int) qz);
                return Float.NaN;
            }
            return col[(int) qy];
        }

        private void noteAnchor(int qx, int qz) {
            anchorQx = qx;
            anchorQz = qz;
            anchorValid = true;
        }

        // ---------------- 学习 ----------------

        private synchronized void observe(double x, double y, double z) {
            if (learned) return;
            long c = ++obsCalls;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
            if (nX < SAMPLE_MAX) nX = addSample(svX, nX, x);
            if (nY < SAMPLE_MAX) nY = addSample(svY, nY, y);
            if (nZ < SAMPLE_MAX) nZ = addSample(svZ, nZ, z);
            if (c >= MIN_CALLS && nX >= 2 && nZ >= 2) {
                tryLearn();
            } else if (c > 20000) {
                // 坐标维度单一/始终无法成格点 → 停止采样，避免每次调用都做 64 样本扫描
                learned = true;
                ok = false;
                learnNote = "样本不足（查询坐标维度单一，无法推断格点）";
                logLearn();
            }
        }

        private static int addSample(double[] arr, int n, double v) {
            for (int i = 0; i < n; i++) {
                if (Math.abs(arr[i] - v) <= 1e-9 * Math.max(1.0, Math.abs(v))) return n;
            }
            if (n < arr.length) {
                arr[n] = v;
                return n + 1;
            }
            return n;
        }

        private void tryLearn() {
            if (learned) return;
            // 先按观测到的最小/最大坐标裁掉明显离群的极值（混合尺度时 min/max 可能跨层）
            double stepX = strideOf(svX, nX, minX, maxX);
            // y 恒定 = 2D 噪声（MC 的 get(DD) 走 get(wrap(x),0,wrap(z))）：可当单层柱服务，
            // 步长取 1、层数 1，lookup 里 (y-by) 必须命中唯一一层。
            boolean flat = (nY < 2) || (maxY - minY <= 1e-12);
            double stepY = flat ? 1.0 : strideOf(svY, nY, minY, maxY);
            double stepZ = strideOf(svZ, nZ, minZ, maxZ);
            if (!(stepX > 0) || !(stepY > 0) || !(stepZ > 0)) {
                learned = true;
                ok = false;
                learnNote = String.format("格点步长无法确定（x=%s y=%s z=%s）",
                        fmt(stepX), fmt(stepY), fmt(stepZ));
                logLearn();
                return;
            }
            int nLayers = flat ? 1 : (int) Math.round((maxY - minY) / stepY) + 1;
            if (nLayers < 1) nLayers = 1;
            if (nLayers > 4096) nLayers = 4096;

            boolean granted;
            synchronized (LOCK) {
                granted = servedSlots < Math.max(1, ShadowShared.maxServed);
                if (granted) { servedSlots++; slotHeld = true; }
            }
            bx = minX; by = minY; bz = minZ;
            dx = stepX; dy = stepY; dz = stepZ;
            ix = 1.0 / stepX; iy = 1.0 / stepY; iz = 1.0 / stepZ;
            layers = nLayers;
            learned = true;
            ok = granted;
            learnNote = granted ? "可服务" : "超出接管槽位上限（" + ShadowShared.maxServed + "）";
            if (granted) takeoverActive = true;
            logLearn();
        }

        /**
         * 从样本集合推断步长：取最小正差，并要求所有样本都落在「起点 + k*步长」上。
         * 混合尺度（同一实例被多个 scale 引用）会失败 → 不可服务（安全降级）。
         */
        private static double strideOf(double[] v, int n, double min, double max) {
            if (n < 2) return -1;
            double[] s = Arrays.copyOf(v, n);
            Arrays.sort(s);
            double best = Double.MAX_VALUE;
            for (int i = 1; i < n; i++) {
                double d = s[i] - s[i - 1];
                if (d > 1e-12 && d < best) best = d;
            }
            if (best == Double.MAX_VALUE) return -1;
            // 所有样本必须是 best 的整数倍
            for (int i = 0; i < n; i++) {
                double q = (s[i] - min) / best;
                if (Math.abs(q - Math.rint(q)) > 1e-6) return -1;
            }
            // max 也必须落在格点上（否则层数会错）
            double qm = (max - min) / best;
            if (Math.abs(qm - Math.rint(qm)) > 1e-6) return -1;
            return best;
        }

        private void logLearn() {
            System.out.println(String.format(
                    "[GPUWorldGen][接管] 实例#%d 格点学习: x[起点=%s 步长=%s] y[起点=%s 步长=%s 层=%d] z[起点=%s 步长=%s] → %s",
                    id, fmt(bx), fmt(dx), fmt(by), fmt(dy), layers, fmt(bz), fmt(dz),
                    ok ? "可服务（开始区域预取）" : learnNote));
            if (ok) System.out.println(String.format(
                    "[GPUWorldGen][接管] 实例#%d 期望 y 覆盖 [%s .. %s]（观测到 %d 层，超出则 CPU fallback）",
                    id, fmt(by), fmt(by + (layers - 1) * dy), layers));
        }

        /** 降级为永久 CPU fallback，并释放占用的接管槽位（失败实例不该长期占坑）。 */
        public void disable(String why) {
            synchronized (LOCK) {
                if (slotHeld) { slotHeld = false; servedSlots--; }
                ok = false;
            }
            learnNote = why;
            System.out.println("[GPUWorldGen][接管] 实例#" + id + " 降级为 CPU fallback：" + why);
        }

        // ---------------- 预取区域调度（渲染线程调用） ----------------

        /** 按内存/时长预算计算区域半径（索引单位）。 */
        public int regionRadius(int maxPasses, int colsPerPass) {
            int byLayers = Math.max(1, layers);
            int targetCols = Math.max(64, colsPerPass * maxPasses);
            int r = (int) ((Math.sqrt((double) targetCols) - 1.0) / 2.0);
            return Math.max(6, Math.min(64, r));
        }

        /** 需要开始/推进一个新区域吗？ */
        public boolean regionNeeded() {
            if (!ok) return false;
            if (regionActive) return true;
            if (!anchorValid) return false;
            int cx = anchorQx, cz = anchorQz;
            if (lastRegionCx == Integer.MIN_VALUE) return true;
            int half = Math.max(4, rW / 2);
            return Math.abs(cx - lastRegionCx) > half || Math.abs(cz - lastRegionCz) > half;
        }

        /** 开启一个以锚点为中心的矩形区域（已缓存的柱跳过安装，但仍参与计算）。 */
        public void beginRegion(int radius) {
            int cx = anchorQx, cz = anchorQz;
            rQx0 = cx - radius;
            rQz0 = cz - radius;
            rW = radius * 2 + 1;
            rH = radius * 2 + 1;
            rRow = 0;
            rCol = 0;
            regionActive = true;
            lastRegionCx = cx;
            lastRegionCz = cz;
        }

        public int maxColumns(int budgetMb) {
            long bytes = (long) budgetMb * 1024L * 1024L;
            int cap = (int) Math.max(1024, bytes / Math.max(1, (long) layers * 4L));
            return Math.min(200_000, cap);
        }
    }

    // ======================= 注册表 API =======================

    /** 全局配置里的接管槽位上限（由 GlComputeRunner 从 ModConfig 注入） */
    static volatile int maxServed = 2;

    public static void configure(int maxServedInstances) {
        maxServed = Math.max(1, maxServedInstances);
    }

    /** mixin 首次遇到某实例时注册；返回 0 表示超限。 */
    public static int register(Object ref, byte[] perms, double ox, double oy, double oz) {
        synchronized (LOCK) {
            if (regCount >= MAX_REG) return 0;
            int id = ++regCount;
            REG[id] = new Instance(id, ref, perms, ox, oy, oz);
            return id;
        }
    }

    public static Instance get(int id) {
        if (id <= 0 || id > MAX_REG) return null;
        return REG[id];
    }

    /** 当前所有可服务实例（渲染线程遍历用）。 */
    public static List<Instance> servable() {
        synchronized (LOCK) {
            List<Instance> out = new ArrayList<>(4);
            for (int i = 1; i <= MAX_REG; i++) {
                Instance s = REG[i];
                if (s != null && s.ok) out.add(s);
            }
            return out;
        }
    }

    /** 注册表容量（mixin 日志用） */
    public static int maxRegister() {
        return MAX_REG;
    }

    public static int registered() {
        synchronized (LOCK) { return regCount; }
    }

    public static int servedCount() {
        synchronized (LOCK) {
            int n = 0;
            for (int i = 1; i <= MAX_REG; i++) if (REG[i] != null && REG[i].ok) n++;
            return n;
        }
    }

    /** 是否有可服务实例（快速判断，避免无谓的 GL 状态保存）。 */
    public static boolean anyServable() {
        synchronized (LOCK) {
            for (int i = 1; i <= MAX_REG; i++) if (REG[i] != null && REG[i].ok) return true;
            return false;
        }
    }

    public static long totalHits() {
        long n = 0;
        for (int i = 1; i <= MAX_REG; i++) if (REG[i] != null) n += REG[i].hits;
        return n;
    }

    public static long totalMisses() {
        long n = 0;
        for (int i = 1; i <= MAX_REG; i++) if (REG[i] != null) n += REG[i].misses;
        return n;
    }

    public static long totalColumns() {
        long n = 0;
        for (int i = 1; i <= MAX_REG; i++) if (REG[i] != null) n += REG[i].cols.size();
        return n;
    }

    public static long totalPasses() {
        long n = 0;
        for (int i = 1; i <= MAX_REG; i++) if (REG[i] != null) n += REG[i].passes;
        return n;
    }

    public static long totalFailPasses() {
        long n = 0;
        for (int i = 1; i <= MAX_REG; i++) if (REG[i] != null) n += REG[i].failPasses;
        return n;
    }

    public static long keyOf(int qx, int qz) {
        return ((long) qx << 32) | (qz & 0xFFFFFFFFL);
    }

    public static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return String.format("%.6f", v);
    }

    private ShadowShared() {}
}
