# 架构与性能分析

本文档记录 M1B-GPUWorldGen 的实现细节，以及**为什么现在的形态是这样**——
包括几条已用真机数据证伪的路线，避免后来者重复踩坑。

---

## 1. 目标函数：MC 的 PerlinNoise

MC 26.3 的 `net.minecraft.world.level.levelgen.synth.PerlinNoise` 继承自
`GradientNoise`，核心方法：

```java
public float get(double x, double y, double z) {
    double x0 = wrap(x) + this.offsetX;
    double y0 = wrap(y) + this.offsetY;
    double z0 = wrap(z) + this.offsetZ;
    int floorX = Mth.floor(x0);
    int floorY = Mth.floor(y0);
    int floorZ = Mth.floor(z0);
    float relX = (float)(x0 - floorX);
    float relY = (float)(y0 - floorY);
    float relZ = (float)(z0 - floorZ);
    return sampleAndLerp(floorX, floorY, floorZ, relX, relY, relZ, relY);
}
```

`wrap(x)` 在 `|x| < 1.6777216E7` 时是恒等映射（只对超大坐标做回绕），
所以现实坐标下可以忽略它——这一点用真机日志核对过。

`sampleAndLerp` 做的是标准 Perlin：

1. `permute(x)/permute(x+1)` 得到 4 个角哈希，再与 `permute(z)` 组合出 8 个梯度哈希；
2. 每个角取 `GRADIENT[h & 15]` 与相对坐标点乘；
3. 用 `Mth.smoothstep`（`t*t*t*(t*(t*6-15)+10)`）做三轴插值 `Mth.lerp3`。

`GRADIENT` 是 16 项常量表，本项目在 shader 里用 `mc_grad` 逐项复刻（包括 12–15 的重复项）。
`perms` 是 256 字节置换表，按 `perms[x & 255] & 255` 使用。

2D 版本 `get(double x, double y)` 直接委托：
`return this.get(wrap(x), 0.0, wrap(y));`——**y 恒为 0**，这也是为什么 2D 噪声在
本项目中按「单层柱」处理。

### 精度问题

GLES 的 `highp float` 只有 24 位尾数，而 MC 的噪声坐标是 double（且叠加了 ~100 量级的
随机 offset）。直接用 float 会在 `x - floor(x)` 上丢掉小数，误差达到 1e-3 量级。

解决办法是 **double-double（DD）**：

- 用两个 float 表示一个 double：`hi + lo`，误差约 `2^-48`；
- 实现 `two_sum` / `two_prod`（Dekker 拆分）、`dd_add`、`dd_sub`、`dd_mul`、`dd_mul_s`；
- 坐标、`fade`、`lerp` 全部在 DD 上做，只在最后输出时压成 `hi + lo` 两通道。

实测 16384 个点最大误差 **5.499e-07**，大坐标（5e6）最大误差 **1.3e-14**。

## 2. GLES 绑定：FFM，而不是 LWJGL

Android 上 LWJGL 的 GLES 绑定不可用（classpath 里没有 `GLES15`），OpenCL 会被
linker namespace 拦截，Vulkan 第二实例会崩。

最终方案：Java **FFM（Foreign Function & Memory，JDK 22+ 正式 API）** 直接：

1. `dlopen("libmobileglues.so")`（或从 `/proc/self/maps` 里找真实路径）；
2. `downcallHandle` 绑定 `glCreateShader` / `glShaderSource` / `glCompileShader` /
   `glCreateProgram` / `glGenTextures` / `glFramebufferTexture2D` / `glDrawArrays` /
   `glReadPixels` / `glFinish` 等约 60 个函数；
3. 全部在**游戏已有的 EGL context** 内执行（渲染线程上），不新建任何 GL 实例。

因为借用的是游戏的上下文，所有调用必须包在 `saveState()` / `restoreState()` 之间：
保存并恢复 FBO 绑定、viewport、program、VAO、active texture、texture binding、
clear color、blend/depth/scissor 开关。漏一个就可能花屏。

## 3. 计算管线

```
CPU                           GPU (fragment shader)                CPU
 │                                    │                            │
 ├─ 上传 permU[64] (256 字节置换表) ──► │                            │
 ├─ 上传坐标基与步长 (dd) ─────────────► │  全屏三角形，128×128 FBO    │
 │                                    │  逐像素用 dd 算 PerlinNoise  │
 │                                    │  输出 RGBA32F: (hi, lo, 0, 0)│
 │                                    │                            │
 │                                 ◄──┴── glFinish + glReadPixels ──┤
 │                                                                 │
 └─ 抽检：与 CpuMcPerlin 逐点比对，通过才写入缓存，否则整批丢弃 ◄────┘
```

shader 里有多个 `uTestMode`：

- `mode 0`：自研 2D 探针（诊断用）
- `mode 1`：自检模式（输出固定值，用来判断 program 是否真的执行了）
- `mode 20`：MC 精确 3D 复刻（用于与 CPU 逐点对拍）
- `mode 22`：批量预取（列 × y 层）

`mode 22` 的坐标链是：

```glsl
dd x0 = dd_add(baseX, dd_mul(dx, mk_dd(float(colx), 0.0)));
dd y0 = dd_add(baseY, dd_mul(dy, mk_dd(float(ySlot), 0.0)));
dd z0 = baseZ;
```

**offset 折进 Java 侧的 double 基坐标**再拆 dd，而不是把 offset 作为 float 上传——
这样与 MC 的 double offset 一致，不会引入 ~2e-6 的额外误差。

## 4. 实例注册与格点学习

`PerlinNoiseShadowMixin` 挂 `get(DDD)F` 的 HEAD。首次调用时：

1. 反射读取父类的 `perms`、`offsetX/Y/Z`（**不用 `@Shadow`**：没有 refMap 时在
   生产环境会崩）；
2. 调 `ShadowShared.register(this, perms, ox, oy, oz)` 拿到实例 id；
3. 之后每次调用走 `instance.offer(x, y, z)`，命中则 `setReturnValue(v)` + `cancel()`。

### 为什么需要「学习格点」

MC 调用噪声时传的是 **方块坐标 × 固定 scale**：

```java
// NoiseFunction.Sampler.sampleValue
return this.noise.get(blockX * this.xzScale, blockY * this.yScale, blockZ * this.xzScale);
```

所以查询点总是落在某个仿射格点上：`x = bx + qx·dx`。但 `(bx, dx)` 事先未知，
同一个 `PerlinNoise` 实例还可能被多个 scale 复用（于是同一实例出现多种步长）。

做法：注册后收集前 N 次真实查询坐标，用「最小正差 + 全样本整数倍校验」反推
`bx/dx`、`by/dy`、`bz/dz` 与 y 层数。任一步长推不出来就**降级为 CPU**，不猜。
y 恒定的 2D 噪声按 `layers = 1` 处理。

服务时：

```java
double tx = (x - bx) / dx;
long qx = Math.round(tx);
if (Math.abs(tx - qx) > tol) return Float.NaN;   // 格点外 → CPU
```

**宁可不服务，也绝不返回错值**——这是整个项目的硬约束。

## 5. 预取调度

预取在渲染线程的每 tick 钩子里跑（`onTick`），受 `prefetchBudgetMs` 约束：

1. 取当前 tick 的第一个 miss 位置作为锚点；
2. 以锚点为中心开一个矩形区域（13×13 到 65×65 格点柱，取决于层数与预算）；
3. 每 tick 推进若干「y 片」：一次 draw 算 `列数 × 每片 y 层数`，只回读实际用到的子矩形；
4. 整批算完后抽检（首/中/尾三层与 CPU 比对），通过才写入缓存；
5. 连续 3 批全失败 → 该实例 `disable()`，永久 CPU fallback 并释放槽位。

`saveState` / `restoreState` 包住整个预取，即使中途抛异常也保证 GL 状态还原。

## 6. 性能经济学：为什么命中率上不去

这是本项目最重要的一节。

从两版真机日志（v0.10.2 与 v0.14.0）做两点回归，解出 GPU 路径的成本模型：

```
T(draw) = F + v · N
F ≈ 898 µs     （每次 draw 的固定开销：MobileGlues 转发 + glFinish + glReadPixels）
v ≈ 61.1 ns    （每个噪声值的边际成本）
```

对照本机 CPU 的实测基准（同一 aarch64 设备，`CpuMcPerlin.get3` 调 300 万次）：

```
CPU ≈ 67.3 ns / 值
```

于是有：

| 场景 | 每值成本 | 对比 CPU |
| --- | --- | --- |
| 稀疏算 25 列 × 49 层 = 1225 值（一个区块的实际需求） | 794 ns | **慢 12 倍** |
| 打满 FBO：128 × 384 × 3 draw = 147456 值 | 115.9 ns | 慢 1.7 倍 |
| 再用 RG32F 回读砍半 | 85.4 ns | 慢 1.3 倍 |
| 理论打平点 | N ≈ 146k 值/draw | — |

结论有三条：

1. **固定开销 F 主导**：每次 draw 近 1 ms，算得越少每值越贵。所以「只算真正需要的点」
   这种稀疏化在本环境下是**负收益**——v0.15.0 的查询模式重放就是这么被证伪的。
2. **即使打满 FBO 也赢不了 CPU**：115.9 ns vs 67.3 ns。这是 MobileGlues 的
   「FBO + 回读」模型决定的，不是参数问题。
3. **命中率低时反而更慢**：miss 的路径多了一次查表 + 一次请求，GPU 那部分开销是纯亏。

这也解释了为什么真机日志里命中率长期在 0%~0.42% 之间：世界初次生成是**一次性爆发**
（8 秒 2000 万次调用），而预取只能在每个渲染 tick 做一点，永远追不上；等预取铺好，
爆发已经结束了。此时预取开销（约 13% 渲染线程时间）是纯损耗。

### 那为什么还要做

因为 MC 26.3 提供了一个**批量入口**，可以一次性摊薄 F：

```java
default void addToVolume(DensityBuffer buffer, DensityVolume volume,
                         double xzScale, double yScale, float amplitude)
```

默认实现是三层循环逐点 `buffer.addTo(amplitude * get(x, y, z))`——正是 2000 万次
`get` 的来源；而 `PerlinNoise` 已经把它重写成不调 `get` 的循环。

一个 `DensityVolume` 是一个区块的整块体素（典型 16×384×16 = **98304 格**）。
如果在这里接管，一次 dispatch 就能覆盖 10 万级格点，把 F 摊到 10 ns/值以下——
这才是结构上能赢 CPU 的路径。

### B 路线的关键未知

`addToVolume` 在**世界生成线程**（Server / Worker）执行，而 GL 上下文只在**渲染线程**。
要么让生成线程阻塞等渲染帧（≥16 ms），要么做双缓冲异步——两条路的可行性取决于
单块真实尺寸与调用频率。

所以 v0.16.0 先上**探针**（只记录、不接管）：

```
[GPUWorldGen][B探针] addToVolume 首次: dim=16x384x16 step=1/1/1 cells=98304 blockBase=(…)
[GPUWorldGen][B探针]   首次 scales: xzScale=… yScale=… amplitude=…
[GPUWorldGen][B探针]   调用线程: Server thread
```

拿到这些数据，才能判断 B 是「一次 dispatch 打满、跨线程等一帧」成立，
还是又回到赶不上爆发的死循环。

## 7. 已证伪的路线（别重复踩）

| 路线 | 结果 |
| --- | --- |
| OpenCL（`libOpenCL.so`） | Android linker namespace 拦截，非 root 不可用 |
| Vulkan 第二实例 | MobileGlues 下必崩 |
| LWJGL desktop compute / GLES15 | classpath 不存在 |
| FFM + SSBO 索引 | MobileGlues 返回全 -1 |
| 逐点稀疏 GPU 计算 | 固定开销反噬，比 CPU 慢 12 倍 |
| 异步按区块补丁预生成 | 永远追不上一次性爆发，命中率 0% |

## 8. 安全设计

- `catch (Throwable)` 包住所有 mixin 注入点与 GL 调用；
- 每批抽检，不一致即丢弃整批；连续失败熔断该实例；
- 预取与自检**串行**，不共用 program 句柄（v0.15.4 的 SIGSEGV 根因）；
- 配置原子写入 + 损坏自修复；
- `gpuTakeover=false` 可以只跑自检、完全不影响世界生成。
