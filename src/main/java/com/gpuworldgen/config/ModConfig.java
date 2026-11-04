package com.gpuworldgen.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * mod 配置文件：<游戏目录>/config/gpuworldgen.json
 *
 * v0.11.0 修复：旧版只在文件不存在时写一次，且是"原地截断写入"。手机端进程经常被
 * 系统/启动器直接杀掉，写入中途被杀就会留下半截 JSON —— 此后每次启动都报
 * `MalformedJsonException: Unterminated object at line 10`（两轮真机日志都出现）。
 * 现在改为：
 *   1) 先写 gpuworldgen.json.tmp，再 ATOMIC_MOVE 替换（fsync 语义，不会出现半截文件）；
 *   2) 解析失败时把损坏文件备份为 gpuworldgen.json.bak，并立刻重建默认配置
 *      （下次启动不再重复报错，用户改过的参数也不会悄悄丢——备份还在）。
 */
public final class ModConfig {

    public boolean enabled = true;

    /**
     * v0.15.3: GPU 预取默认**关闭**。
     *
     * 真机上 v0.15.0/v0.15.2 都会在进世界后 SIGSEGV（崩溃点在 libc 的 __memmove，
     * 是 native 层越界写，Java 侧无法兜住）。在彻底定位之前，默认走纯 CPU 世界生成
     * —— 游戏一定能进、地形一定正确，只是没有 GPU 加速。
     *
     * 想继续试验就把它改成 true（风险自负，并请把崩溃日志发回）。
     */
    public boolean gpuTakeover = false;

    /** 随机种子（permutation 表，仅用于自研 CPU 参考噪声，不影响 MC 原版噪声） */
    public long seed = 42L;

    /** 开机自检采样网格边长（size x size 个采样点） */
    public int size = 128;

    /** 噪声频率（自检网格缩放） */
    public int freq = 4;

    /** 工作组大小，0 = 自动（64） */
    public int workGroupSize = 64;

    /** 首选设备类型：gpu / cpu / any */
    public String deviceType = "gpu";

    /** 尝试加载的 OpenCL 库名（旧版 OpenCL 路线，保留兼容） */
    public String libraryName = "libOpenCL.so";

    /** 是否写验证报告文件 */
    public boolean writeReport = true;

    // ---------- v0.11.0 GPU 接管（格点学习 + 区域预取）参数 ----------

    /** 最多接管几个 3D 噪声实例（每个实例独立缓存在显存/内存外，按格点学习结果决定） */
    public int maxServedInstances = 6;

    /** 每 tick 最多跑几个预取批次（每批 = 一次 draw + 一次回读，约 2-4ms） */
    public int prefetchPassesPerTick = 6;

    /** 一次区域预取最多跑多少批（限制单次预取的时长与显存占用） */
    public int prefetchMaxPasses = 48;

    /** 每批预取最多算多少个格点（像素数）；越大越省 draw 次数，越大越卡帧 */
    public int prefetchPointsPerPass = 2048;

    /** 每 tick 最多花多少毫秒在区域预取上（控制帧耗时，超了就留到下个 tick） */
    public double prefetchBudgetMs = 4.0;

    /** 命中判定容差（格点索引空间，无量纲）：|坐标/步长 - 最近整数| 必须小于它才服务 */
    public double serveTolerance = 1e-6;

    /** 列缓存内存预算（MB，超限清空重来） */
    public int cacheBudgetMb = 48;

    /** 进世界后延迟多少 tick 再跑开机自检（让世界生成的预取先跑起来，避免自检抢占渲染线程） */
    public int verifyDelayTicks = 40;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ModConfig load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path file = configDir.resolve("gpuworldgen.json");
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            System.err.println("[GPUWorldGen] 无法创建配置目录: " + e);
        }

        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                ModConfig cfg = GSON.fromJson(json, ModConfig.class);
                if (cfg == null) throw new IllegalStateException("配置内容为空");
                cfg.sanitize();
                return cfg;
            } catch (Throwable e) {
                // v0.11.0: 备份损坏文件 + 重建，避免每次启动都报同一句解析错误
                Path backup = configDir.resolve("gpuworldgen.json.bak");
                try {
                    Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    System.err.println("[GPUWorldGen] 配置已损坏（" + e.getMessage()
                            + "），已备份为 " + backup.getFileName() + " 并重建默认配置。");
                } catch (IOException ioe) {
                    System.err.println("[GPUWorldGen] 配置已损坏且无法备份: " + ioe);
                }
                ModConfig cfg = new ModConfig();
                cfg.sanitize();
                save(configDir, file, cfg);
                return cfg;
            }
        }

        ModConfig cfg = new ModConfig();
        cfg.sanitize();
        save(configDir, file, cfg);
        return cfg;
    }

    /** v0.11.0: 原子写（tmp + ATOMIC_MOVE），进程被强杀也不会留下半截 JSON。 */
    private static void save(Path dir, Path file, ModConfig cfg) {
        Path tmp = dir.resolve("gpuworldgen.json.tmp");
        try {
            Files.writeString(tmp, GSON.toJson(cfg), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ae) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[GPUWorldGen] 无法写入配置: " + e);
        }
    }

    private void sanitize() {
        // v0.11.0: shader 的 uv 网格与验证参考网格都固定 128x128（mode20/mode22 都按像素索引），
        // 配置里的 size 仅作兼容保留；改成别的值只会让对拍网格错位。
        size = 128;
        if (freq < 1) freq = 1;
        if (freq > 128) freq = 128;
        if (workGroupSize < 1) workGroupSize = 64;
        if (workGroupSize > 1024) workGroupSize = 1024;
        if (maxServedInstances < 1) maxServedInstances = 1;
        if (maxServedInstances > 6) maxServedInstances = 6;
        if (prefetchPassesPerTick < 1) prefetchPassesPerTick = 1;
        if (prefetchPassesPerTick > 32) prefetchPassesPerTick = 32;
        if (prefetchMaxPasses < 4) prefetchMaxPasses = 4;
        if (prefetchMaxPasses > 512) prefetchMaxPasses = 512;
        if (prefetchPointsPerPass < 128) prefetchPointsPerPass = 128;
        if (prefetchPointsPerPass > 16384) prefetchPointsPerPass = 16384;
        if (!(prefetchBudgetMs > 0.1)) prefetchBudgetMs = 3.0;
        if (prefetchBudgetMs > 16.0) prefetchBudgetMs = 16.0;
        if (serveTolerance < 1e-9) serveTolerance = 1e-9;
        if (serveTolerance > 1e-3) serveTolerance = 1e-3;
        if (cacheBudgetMb < 4) cacheBudgetMb = 4;
        if (cacheBudgetMb > 256) cacheBudgetMb = 256;
        if (verifyDelayTicks < 0) verifyDelayTicks = 0;
        if (verifyDelayTicks > 1200) verifyDelayTicks = 1200;
    }
}
