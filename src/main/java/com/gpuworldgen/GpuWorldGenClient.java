package com.gpuworldgen;

import com.gpuworldgen.config.ModConfig;
import com.gpuworldgen.verify.VerifyRunner;
import net.fabricmc.api.ClientModInitializer;

/**
 * GPU WorldGen — 手机端 GPU 世界生成适配（Adreno Adapter）
 *
 * 第一版（0.1.0）：验证 OpenCL 可用性 + double-double 精度。
 * 后台线程执行，绝不阻塞游戏启动。
 */
public class GpuWorldGenClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        System.out.println("[GPUWorldGen] mod 加载 v0.15.3 (M1B-GPUWorldGen, GPU接管默认关闭以规避 native 崩溃)");
        ModConfig config = ModConfig.load();
        if (!config.enabled) {
            System.out.println("[GPUWorldGen] 已禁用（config/gpuworldgen.json 中 enabled=false）。");
            return;
        }
        Thread worker = new Thread(() -> new VerifyRunner(config).run(), "gpu-worldgen-verify");
        worker.setDaemon(true);
        worker.start();
    }
}
