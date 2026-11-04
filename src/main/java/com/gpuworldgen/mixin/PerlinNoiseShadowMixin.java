package com.gpuworldgen.mixin;

import com.gpuworldgen.shadow.ShadowShared;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GPU 接管 hook：拦截真实 MC PerlinNoise 的噪声查询。
 *
 * <h3>v0.10.x</h3>
 * 只有一个「服务实例」，用 floor(坐标) 当柱号、异步按区块预生成 16x16x384 整数柱补丁。
 * 真机日志实证命中率 0.0%（833600 次调用 / 0 hit）：MC 的噪声是按<b>格点</b>采样的
 * （坐标 = 方块坐标 × xzScale/yScale，y 步长常常不是 1），而区块初次生成是毫秒级的
 * 一次性爆发，异步补丁永远追不上。
 *
 * <h3>v0.11.0</h3>
 * 每个被查询的 PerlinNoise 实例都注册到 {@link ShadowShared}，各自学习自己的采样格点
 * （起点 / 步长 / 层数），只对「严格落在格点上」的查询返回 GPU 值；其余一律 CPU fallback。
 * 缓存命中的判断在 {@link ShadowShared.Instance#offer} 内完成，异常一律吞掉不 cancel。
 *
 * <h3>为什么用反射而不是 @Shadow</h3>
 * perms/offsetX/Y/Z 是父类 GradientNoise 的 protected 字段。mixin 的 @Shadow 默认只在目标类
 * 自身查找字段（不自动上查父类），且本 mod 无 refMap，@Shadow 会抛 InvalidMixinException
 * 导致 required:true 崩溃。因此改用反射读父类字段（mojmap 字段名生产一致）。
 */
@Mixin(PerlinNoise.class)
public abstract class PerlinNoiseShadowMixin {

    private static final AtomicLong CALL = new AtomicLong();
    /** 每 256 次 get 调用收集一个采样坐标（2 的幂，用位运算节流） */
    private static final long STRIDE = 256;

    // 父类 GradientNoise 字段的反射句柄（懒初始化，只做一次）
    private static volatile Field F_PERMS, F_OX, F_OY, F_OZ;
    private static volatile boolean fieldsReady;
    private static volatile boolean diag2d;
    private static volatile boolean diag3d;

    /** 本实例在 ShadowShared 注册表中的 id（0=未注册，-1=注册失败不再重试） */
    private volatile int gpuworldgen$id;

    private static void ensureFields() {
        if (fieldsReady) return;
        synchronized (PerlinNoiseShadowMixin.class) {
            if (fieldsReady) return;
            try {
                Class<?> g = net.minecraft.world.level.levelgen.synth.GradientNoise.class;
                F_PERMS = g.getDeclaredField("perms");
                F_OX = g.getDeclaredField("offsetX");
                F_OY = g.getDeclaredField("offsetY");
                F_OZ = g.getDeclaredField("offsetZ");
                F_PERMS.setAccessible(true);
                F_OX.setAccessible(true);
                F_OY.setAccessible(true);
                F_OZ.setAccessible(true);
                fieldsReady = true;
                System.out.println("[GPUWorldGen][mixin] 反射字段就绪: perms/offsetX/Y/Z");
            } catch (Throwable t) {
                System.out.println("[GPUWorldGen][mixin] 反射字段失败: " + t);
            }
        }
    }

    /**
     * 注册本实例并返回 id（0=不可用）。
     * 首个注册的实例同时作为 2D 影子对拍的参考实例。
     */
    /** 只注册一次（并发首个调用可能来自多个区块线程，必须互斥，否则同一实例会占两个 id） */
    private synchronized int gpuworldgen$registerOnce() {
        int cur = gpuworldgen$id;
        if (cur != 0) return cur;
        int id = gpuworldgen$register();
        gpuworldgen$id = id;
        return id;
    }

    private int gpuworldgen$register() {
        ensureFields();
        if (!fieldsReady) return -1;
        try {
            byte[] perms = (byte[]) F_PERMS.get((Object) this);
            if (perms == null || perms.length != 256) return -1;
            double ox = F_OX.getDouble(this);
            double oy = F_OY.getDouble(this);
            double oz = F_OZ.getDouble(this);
            if (!ShadowShared.ready) {
                ShadowShared.supplyRealData(perms, ox, oy, oz);
            }
            int id = ShadowShared.register(this, perms, ox, oy, oz);
            if (id == 0) {
                System.out.println("[GPUWorldGen][mixin] 实例注册超限（>" + ShadowShared.maxRegister() + "），本实例永久 CPU fallback");
                return -1;
            }
            System.out.println("[GPUWorldGen][mixin] 注册 PerlinNoise 实例 #" + id
                    + " offsetX=" + ox + " offsetY=" + oy + " offsetZ=" + oz);
            return id;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Inject(method = "get(DD)F", at = @At("HEAD"))
    private void gpuworldgen$shadow(double x, double z, CallbackInfoReturnable<Float> cir) {
        try {
            if (!diag2d) {
                diag2d = true;
                System.out.println("[GPUWorldGen][mixin] 已命中 get(DD)F，首次调用 (x=" + x + " z=" + z + ")");
            }
            long c = CALL.incrementAndGet();
            if ((c & (STRIDE - 1)) == 0L) {
                ShadowShared.offer(x, z);
            }
        } catch (Throwable ignored) {
            // 影子模式绝不干扰游戏
        }
    }

    @Inject(method = "get(DDD)F", at = @At("HEAD"))
    private void gpuworldgen$takeover3(double x, double y, double z, CallbackInfoReturnable<Float> cir) {
        try {
            int id = gpuworldgen$id;
            if (id == 0) {
                if (!diag3d) {
                    diag3d = true;
                    System.out.println("[GPUWorldGen][mixin] 已命中 get(DDD)F，首次调用 (x=" + x + " y=" + y + " z=" + z + ")");
                }
                id = gpuworldgen$registerOnce();
                if (id <= 0) return;
            }
            if (id < 0) return;
            ShadowShared.Instance inst = ShadowShared.get(id);
            if (inst == null) return;
            float v = inst.offer(x, y, z);
            if (v == v) {
                cir.setReturnValue(v);
                cir.cancel();                 // 跳过 CPU 原方法 → GPU 值真正被采用
            }
        } catch (Throwable ignored) {
            // 任何异常都不 cancel → 走 CPU 原方法
        }
    }
}
