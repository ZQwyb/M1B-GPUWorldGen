# M1B-GPUWorldGen

**在手机的 GPU 上跑 Minecraft Java 版的世界生成噪声。**
面向没有 PC 的玩家（PojavLauncher / Zalith Launcher / MobileGlues），把 `PerlinNoise`
的计算下沉到 Adreno GPU，并保证与原版 **逐点一致（误差 < 1e-6）**。

[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric%20Loader-0.20.1-DBB69B)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25-E76F00)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

---

## 这是什么

手机端 MC Java 版没有桌面级的并行计算能力，世界生成（尤其是 3D 密度噪声）是进世界时最重的
CPU 负载之一。本项目用 Java FFM 在游戏的 EGL 上下文里直接调用 GLES，用 **fragment shader +
double-double 模拟双精度**复刻 MC 的 `PerlinNoise`，把噪声值从 GPU 取回来供世界生成使用。

它不替换世界生成器、不改存档格式、不打包任何 Minecraft 代码——只是一个 **mixin 层面的
噪声加速适配层**，失效时自动退回原版 CPU 路径。

## 当前状态

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| GLES double-double 噪声管线 | ✅ 稳定 | 5 轮 STABLE，大坐标（5e6）误差 1.3e-14 |
| MC 精确噪声复刻（3D 16 梯度） | ✅ 对齐 | 16384 点 maxErr 5.5e-7，0/16384 超 1e-6 |
| 真实实例影子比对 | ✅ 验收 | 真实坐标 maxErr 3.4e-7，mismatch 0 |
| GPU 接管 `PerlinNoise.get(DDD)F` | ✅ 可用 | 命中则 `setReturnValue + cancel`，未命中 CPU fallback |
| 格点学习 + 区域批量预取 | ✅ 可用 | 自动推断实例采样格点，只服务严格落在格点上的查询 |
| 点选/抽检门禁 | ✅ 可用 | 抽检与 CPU 精确参考不一致 → 该实例永久降级，绝不返回错值 |
| **批量接管 `addToVolume`（B 路线）** | 🧪 探针阶段 | v0.16.0 只做观察，不改行为；数据回收中 |

> **v0.16.0 是一个「探针版」**：它不改变任何世界生成行为，只在 `PerlinNoise.addToVolume`
> 上记录形状/步长/线程信息，用于决定下一代批量接管是否可行。稳定性和 v0.15.4 相同。

### 关于命中率：一句实话

GPU 接管命中率在真机上**长期偏低**（历史最好 0.42%，多数时间接近 0）。这不是调参问题，
而是架构问题，我已经用两版真机日志把它量化清楚了：

- 每次 draw 的固定开销约 **0.9 ms**（MobileGlues 转发 + `glReadPixels` 回读），
  每值边际成本约 **61 ns**；
- 同一台手机的 CPU 单线程算同一函数只要 **67 ns/值**（实测 300 万次平均）。

也就是说：**在当前 MobileGlues 的「FBO + 回读」模型下，GPU 算噪声并不比 CPU 快**；
命中率低时反而更慢。真正要赢，必须走 MC 26.3 新增的 `addToVolume` 批量入口，
把每次 draw 的固定开销摊薄到几万个格点上——这正是 v0.16.0 探针要验证的事。

详细的推导过程见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 技术路线

- **FFM 直接绑定 GLES**：`dlopen` 手机上的 `libmobileglues.so`，`bind` 导出函数，
  在游戏已有的 EGL context 内执行；不新建 GL/Vulkan 实例，不引入额外 native 库。
- **double-double 精度**：GLES 只有 float，shader 里用 `two_sum` / `two_prod` /
  `dd_mul` / `dd_fade` / `dd_lerp` 组合出约 48 位有效精度，复刻 MC 的 `Mth.lerp3` +
  `Mth.smoothstep` 流程，与 Java 的 `float` 结果逐点对齐。
- **mixin + 反射**：挂 `PerlinNoise.get(DDD)F`，反射读取父类 `GradientNoise` 的
  `perms` / `offsetX/Y/Z`（不依赖 refMap，避免混淆环境下崩），按实例注册、按格点服务。
- **安全阀**：所有 GL 调用包在 `saveState` / `restoreState` 里；每批抽检；连续失败熔断；
  任何异常都 `catch (Throwable)` 后回归 CPU。

**已判死的路线（别重复踩）**：OpenCL（Android linker namespace 拦截）、Vulkan 第二实例（必崩）、
桌面 compute（stub）、SSBO（MobileGlues 索引全 -1）、逐点 FBO 稀疏计算（固定开销反噬）。

## 支持环境

- Minecraft **26.3** / Fabric Loader **0.20.1** / **Java 25**
- 渲染后端：**OpenGLES / MobileGL**（MobileGlues 2.x，GLES 3.2）
  > 不要用 DirectVulkan：本项目基于 EGL + GLES 绑定，Vulkan 模式下功能不可用。
- 参考真机：HONOR AAK-AN00（骁龙 8 Elite / Adreno 830）

## 构建

```bash
export JAVA_HOME=/path/to/jdk-25
export LANG=C.utf8 LC_ALL=C.utf8    # 中文路径下必须，否则 Gradle 报 locale 错误
./gradlew build --no-daemon --console=plain
# 产物：build/libs/m1b-gpuworldgen-<version>.jar
```

需要 JDK **25**（MC 26.3 的要求）、Gradle 9.8+（wrapper 已内置）、Loom 1.19-SNAPSHOT。

## 安装

1. 把 `m1b-gpuworldgen-<version>.jar` 放进 `mods/`（**不要**同时放旧版，包名相同会互相覆盖）；
2. 渲染后端选 **OpenGLES / MobileGL**；
3. 进游戏 → 进世界，模组会等世界加载完成后开始 GPU 自检与（可选的）接管；
4. 自检报告写在游戏目录的 `gpu-worldgen-verify.txt`。

## 配置

配置文件：`config/gpuworldgen.json`（不存在时自动创建；文件被写坏会自动备份成
`gpuworldgen.json.bak` 并重建默认值）。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关（关闭后完全不加载 GL） |
| `gpuTakeover` | `true` | 是否真正接管世界生成噪声（关掉只跑自检，最稳） |
| `maxServedInstances` | `6` | 同时接管的噪声实例上限（1~6） |
| `prefetchPassesPerTick` | `6` | 每 tick 最多推进几批预取 |
| `prefetchPointsPerPass` | `2048` | 单批目标格点数（决定批宽度） |
| `prefetchMaxPasses` | `48` | 一个预取区域的目标批数 |
| `prefetchBudgetMs` | `4.0` | 每 tick 预取时间预算（毫秒） |
| `cacheBudgetMb` | `48` | 每个实例的柱缓存预算 |
| `serveTolerance` | `1e-6` | 判定「落在格点上」的容差 |
| `verifyDelayTicks` | `40` | 进世界后延迟多少 tick 开始自检 |
| `writeReport` | `true` | 是否写 `gpu-worldgen-verify.txt` |
| `size` | `128` | 固定 128（shader 网格与对拍网格一致，其他值会被忽略） |
| `freq` / `workGroupSize` / `deviceType` / `libraryName` | — | 早期实验参数，保留兼容 |

## 日志与验证

进世界后自检会打印（`latest.log` 中搜索 `GPUWorldGen`）：

```
[GPUWorldGen] GPU 接管 v0.13: 提供噪声 0.30% (360320 hit / 118376326 调用, 格点外 1104348)
              | 实例 46 注册 / 4 可服务 | 缓存柱=4063 | 预取 14560 批 / 失败 0 批
[GPUWorldGen] 区域预取耗时: 均 1.84 ms/片 (draw+read+抽检), 均 1.4 柱/片
[GPUWorldGen]   实例#1: 可服务 | 格点 x=-64/0.125 y=-64/0.125(1023层) z=-63.625/0.125 | hit=… miss=… 越界=…
```

v0.16.0 额外输出 B 路线探针数据：

```
[GPUWorldGen][B探针] addToVolume 首次: dim=16x384x16 step=1/1/1 cells=98304 blockBase=(…)
[GPUWorldGen][B探针]   首次 scales: xzScale=… yScale=… amplitude=…
[GPUWorldGen][B探针]   调用线程: Server thread
```

## 已知限制

- 只在 **MobileGlues + OpenGLES** 后端验证过；其他 GL 驱动（尤其真实 GLES 驱动）未测。
- GL 上下文属于渲染线程，预取只能每 tick 做一点，**无法追上进世界时的一次性生成爆发**。
- 逐点命中模型在架构上有上限（见上文「关于命中率」），批量 `addToVolume` 路径是下一代方向。
- 模组会修改 `config/gpuworldgen.json` 与写出验证报告，不做其他持久化改动。

## 路线图

- **v0.16.x**：回收 `addToVolume` 探针数据（形状/线程/scale）→ 评估批量接管可行性
- **v0.17+**：若批量路径成立 → 整块体素在 GPU 一次算完，用跨线程通道回传；否则转向 PBO 异步回读 + 降低固定开销
- **远期**：Vulkan 后端（接口已预留）、多区块流水线
- 跟随 MC 26.x 持续适配

## 文档

- [架构与性能分析](docs/ARCHITECTURE.md) —— 噪声数学、FFM 绑定、逐版根因推导
- [更新日志](docs/CHANGELOG.md) —— 每个版本改了什么、为什么、怎么验证

## 协议

MIT License，见 [LICENSE](LICENSE)。

---

**免责声明**：本项目为独立的 Fabric mod，仅为噪声计算适配层；不包含、不打包、不分发
Minecraft 本体或其资源文件。Minecraft 是 Mojang Studios 的商标。
