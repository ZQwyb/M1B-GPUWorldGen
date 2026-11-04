package com.gpuworldgen.volume;

import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v0.16.0 · B 路线探针：只统计 {@code PerlinNoise.addToVolume} 的调用特征，不做任何 GPU 操作。
 *
 * <p>要回答的问题（决定 GPU 批量通道怎么建）：</p>
 * <ol>
 *   <li>单个 volume 有多少格子（sizeX×sizeY×sizeZ），形状是「薄片」还是「全高柱」；</li>
 *   <li>块坐标与步长（minBlock*、stepBlock*）——决定 GPU 端坐标怎么铺、能不能一次 draw 打满；</li>
 *   <li>xzScale / yScale 的取值——决定噪声的格点相位；</li>
 *   <li>调用线程——决定 GPU 上下文能否直接触及（MobileGlues 单 context 只在渲染线程）。</li>
 * </ol>
 */
public final class VolumeProbe {

    /** 首次计数的块尺寸特征（用于日志） */
    private static final AtomicReference<String> FIRST_SHAPE = new AtomicReference<>(null);
    private static final AtomicReference<String> FIRST_SCALES = new AtomicReference<>(null);
    private static final AtomicReference<String> FIRST_THREAD = new AtomicReference<>(null);
    /** 总块调用数 / 总格子数 */
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong CELLS = new AtomicLong();
    /** 采样过的不同形状（去重） */
    private static final java.util.Set<String> SHAPES = new java.util.HashSet<>();
    private static final Object LOCK = new Object();

    /** 每个 addToVolume 块调用一次。 */
    public static void record(Object self, DensityVolume v, double xz, double ys, float amp) {
        long cells = (long) v.sizeX() * v.sizeY() * v.sizeZ();
        CALLS.incrementAndGet();
        CELLS.addAndGet(cells);

        String shape = "dim=" + v.sizeX() + "x" + v.sizeY() + "x" + v.sizeZ()
                + " step=" + v.stepBlockX() + "/" + v.stepBlockY() + "/" + v.stepBlockZ()
                + " cells=" + cells;
        boolean newShape;
        synchronized (LOCK) {
            newShape = SHAPES.size() < 8 && SHAPES.add(shape);
        }

        if (FIRST_SHAPE.compareAndSet(null, shape)) {
            System.out.println("[GPUWorldGen][B探针] addToVolume 首次: " + shape
                    + " blockBase=(" + v.minBlockX() + "," + v.minBlockY() + "," + v.minBlockZ() + ")");
            String scales = "xzScale=" + xz + " yScale=" + ys + " amplitude=" + amp;
            FIRST_SCALES.set(scales);
            FIRST_THREAD.set(Thread.currentThread().getName());
            System.out.println("[GPUWorldGen][B探针]   首次 scales: " + scales);
            System.out.println("[GPUWorldGen][B探针]   调用线程: " + Thread.currentThread().getName()
                    + " (isRender=" + Thread.currentThread().getName().contains("Render") + ")");
        }

        if (newShape && SHAPES.size() <= 8) {
            System.out.println("[GPUWorldGen][B探针] 新形状#" + SHAPES.size() + ": " + shape);
        }

        // 每 4096 块打一次累计
        long c = CALLS.get();
        if ((c & 4095) == 0) {
            System.out.println("[GPUWorldGen][B探针] 累计 " + c + " 块 / " + CELLS.get()
                    + " 格子，均 " + (CELLS.get() / Math.max(1, c)) + " 格子/块");
        }
    }

    /** 探针汇总（供报告/统计行调用）。 */
    public static String summary() {
        long c = CALLS.get();
        return "addToVolume 累计 " + c + " 块 / " + CELLS.get() + " 格子"
                + (c > 0 ? "，均 " + (CELLS.get() / c) + " 格子/块" : "")
                + "；首次 " + FIRST_SHAPE.get()
                + "；scales " + FIRST_SCALES.get();
    }

    private VolumeProbe() {}
}