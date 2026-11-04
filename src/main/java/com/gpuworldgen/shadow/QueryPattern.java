package com.gpuworldgen.shadow;

/**
 * v0.15.0: 一个区块内某个噪声实例的**真实查询模式**。
 *
 * <h3>为什么需要它（v0.14 日志实证）</h3>
 * v0.14 的预取是矩形全扫：45×45 柱 × 1023 层 = 2,073,825 个点/区域。
 * 而一个区块对该实例的真实需求只有 5×5×49 ≈ 1225 个点 —— 算出来的 99.9% 是白算的，
 * 日志里因此出现「均 1.1 柱/片」（一片 128 列却只有 1.1 列真被用到），命中率卡在 0.42%。
 *
 * <h3>做法：记录相对偏移，按区块平移重放</h3>
 * 记录每个真实查询点相对「本区块最小格点坐标 (minQx,minQy,minQz)」的偏移；
 * 下一个区块用它的最小值做新基点，加上同样的偏移，就得到该预取哪些点。
 * 这样**与采样尺度无关**（0.125 / 0.25 / 混合尺度都成立），因为它只依赖区块内的相对结构。
 *
 * 容量：最多 {@value #CAP} 个偏移点；超出视为模式不规则，退回 v0.14 的矩形扫描。
 */
public final class QueryPattern {

    /** 最多记录多少个偏移点（一个区块对单实例通常 1000~2000 个，留余量） */
    public static final int CAP = 4096;

    /** 偏移（相对本区块 minQx/minQy/minQz），int 足够（区块内格点数 < 2000） */
    private final short[] offX = new short[CAP];
    private final short[] offY = new short[CAP];
    private final short[] offZ = new short[CAP];
    private int n;
    /** 基点（本区块观察到的最小格点坐标） */
    private int baseX, baseY, baseZ;
    private boolean baseSet;
    /** 偏移溢出 → 不可用 */
    public boolean overflow;

    public volatile long samples;

    /** 记录一次真实查询（mixin 热路径，synchronized 保护）。 */
    public synchronized void record(int qx, int qy, int qz) {
        samples++;
        if (!baseSet) {
            baseX = qx; baseY = qy; baseZ = qz;
            baseSet = true;
        }
        if (qx < baseX) shiftBase(qx, baseY, baseZ);
        if (qy < baseY) shiftBase(baseX, qy, baseZ);
        if (qz < baseZ) shiftBase(baseX, baseY, qz);
        int dx = qx - baseX, dy = qy - baseY, dz = qz - baseZ;
        if (dx > Short.MAX_VALUE || dy > Short.MAX_VALUE || dz > Short.MAX_VALUE) {
            overflow = true;
            return;
        }
        // 去重（线性查，n 最多 4096，实际样本常 <2000；只在学习期做，代价可接受）
        for (int i = 0; i < n; i++) {
            if (offX[i] == dx && offY[i] == dy && offZ[i] == dz) return;
        }
        if (n >= CAP) { overflow = true; return; }
        offX[n] = (short) dx;
        offY[n] = (short) dy;
        offZ[n] = (short) dz;
        n++;
    }

    /** 基点下移时，把所有已有偏移同步平移（保持相对结构不变）。 */
    private void shiftBase(int nx, int ny, int nz) {
        int ddx = baseX - nx, ddy = baseY - ny, ddz = baseZ - nz;
        baseX = nx; baseY = ny; baseZ = nz;
        if (ddx == 0 && ddy == 0 && ddz == 0) return;
        for (int i = 0; i < n; i++) {
            int vx = offX[i] + ddx, vy = offY[i] + ddy, vz = offZ[i] + ddz;
            if (vx > Short.MAX_VALUE || vy > Short.MAX_VALUE || vz > Short.MAX_VALUE) { overflow = true; return; }
            offX[i] = (short) vx;
            offY[i] = (short) vy;
            offZ[i] = (short) vz;
        }
    }

    /** 换区块后重置记录（保留 overflow 标志以便永久降级）。 */
    public synchronized void reset() {
        n = 0;
        baseSet = false;
        samples = 0;
        // overflow 不清：一旦判定不规则就不再记录
    }

    public boolean usable() {
        return !overflow && bagged() && n > 0;
    }

    /** 是否已有足够样本（≥64 个点，避免一两个早期样本就重放）。 */
    public boolean bagged() { return samples >= 64; }

    public int size() { return n; }

    /** 按「新区块基点」平移出待预取的点集。返回 null 表示不可用。 */
    public int[] replay(int newBaseX, int newBaseY, int newBaseZ) {
        if (!usable()) return null;
        int[] out = new int[n * 3];
        for (int i = 0; i < n; i++) {
            out[i * 3] = newBaseX + offX[i];
            out[i * 3 + 1] = newBaseY + offY[i];
            out[i * 3 + 2] = newBaseZ + offZ[i];
        }
        return out;
    }

    /** 只取 (qx,qz) 列集合（去重），y 用整段范围 —— 供列式预取使用。 */
    public int[] replayColumns(int newBaseX, int newBaseZ) {
        if (!usable()) return null;
        int[] tmp = new int[n];      // qx,qz 交错
        int m = 0;
        for (int i = 0; i < n && m < n; i++) {
            int qx = newBaseX + offX[i];
            int qz = newBaseZ + offZ[i];
            boolean dup = false;
            for (int j = 0; j < m; j += 2) {
                if (tmp[j] == qx && tmp[j + 1] == qz) { dup = true; break; }
            }
            if (!dup) { tmp[m++] = qx; tmp[m++] = qz; }
        }
        int[] out = new int[m];
        System.arraycopy(tmp, 0, out, 0, m);
        return out;
    }

    /** 记录到的相对 y 跨度（用于确定每列要算多少层）。 */
    public int ySpanDy(int newBaseY) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int v = offY[i];
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        return hi < lo ? 0 : (hi - lo + 1);
    }

    public int yOffLo() {
        int lo = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) if (offY[i] < lo) lo = offY[i];
        return lo == Integer.MAX_VALUE ? 0 : lo;
    }

    public int yOffHi() {
        int hi = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) if (offY[i] > hi) hi = offY[i];
        return hi == Integer.MIN_VALUE ? 0 : hi;
    }

    public int baseX() { return baseX; }
    public int baseY() { return baseY; }
    public int baseZ() { return baseZ; }
    public boolean baseSet() { return baseSet; }

    /** 诊断文本。 */
    public String describe() {
        if (!baseSet) return "无样本";
        return String.format("样本=%d 唯一点=%d %s", samples, n,
                overflow ? "溢出(不可用)" : "可用");
    }
}
