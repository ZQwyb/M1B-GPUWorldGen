package com.gpuworldgen.mixin;

import com.gpuworldgen.volume.VolumeProbe;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v0.16.0 · B 路线探针（只观察，不改任何行为）。
 *
 * <p>MC 26.3 世界生成的噪声批量入口是 {@code Noise.addToVolume}：一次调用带一整个
 * {@link DensityVolume}（一个区块 × 全高，几千到几万个格子）和 {@link DensityBuffer}。
 * 它的默认实现是逐点循环调 {@code get(x,y,z)} —— 也就是我们之前一直用「逐点缓存」去对抗
 * 的那个批量爆发（日志里 8 秒 2000 万次 get 的来源）。</p>
 *
 * <p>本 mixin 挂在 {@code PerlinNoise.addToVolume} 的 HEAD，只记录：
 * volume 的尺寸/块坐标/步长、调用线程、单块格子数、频率（限流）。<b>不 cancel、不改 buffer、
 * 不碰 GL</b>，因此零风险。拿到数据后再决定 GPU 批量通道怎么建。</p>
 */
@Mixin(PerlinNoise.class)
public abstract class PerlinNoiseVolumeProbeMixin {

    @Inject(method = "addToVolume", at = @At("HEAD"))
    private void gpuworldgen$probeVolume(DensityBuffer buffer, DensityVolume volume,
                                        double xzScale, double yScale, float amplitude,
                                        CallbackInfo ci) {
        VolumeProbe.record(this, volume, xzScale, yScale, amplitude);
    }
}