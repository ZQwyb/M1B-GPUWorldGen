# M1B-GPUWorldGen

为 **Minecraft Java 版（手机端）** 实现真正 GPU 加速世界生成的 Fabric 适配 mod。

面向无 PC 的手机玩家（Pojav / Zalith Launcher 等），把世界生成里的 PerlinNoise 噪声计算
下沉到手机 GPU（Adreno）执行，并保证与 MC 原版噪声 **逐点一致（误差 < 1e-6）**。

> 当前处于 **GPU 接管阶段**：v0.13.0 改为「格点学习 + 128 列满带宽批量预取 + 抽检门禁 + y 窗口按需扩容」——
> 自动推断每个噪声实例的采样格点，只服务严格落在格点上的查询，其余走 CPU fallback。
> 宁可不服务，也绝不返回错值。

---

## 能力与当前状态

| 阶段 | 状态 | 结果 |
|---|---|---|
| GLES double-double 噪声管线（fragment shader + FBO） | ✅ 已交付 | 5 轮 STABLE，大坐标误差 ~1e-14 |
| MC 精确噪声 GPU 复刻（PerlinNoise 3D 16 梯度） | ✅ 已交付 | maxErr 5.499e-07，0/16384（超大坐标 5e6） |
| 影子模式（真实实例逐点比对） | ✅ 已验收 | 真实坐标 maxErr 3.43e-07，mismatch 0 |
| double-double 坐标（>2e6 不再丢小数） | ✅ v0.9.0 | 拆分重建误差 1.9e-09，精度上限 2^24 |
| GPU 接管世界生成（首个实例） | ✅ v0.10.1 | mixin cancel 真返回 GPU 值 + CPU fallback + 预生成抽检 |
| 多实例接管 + 格点学习 | ✅ v0.11.0 | 每个 PerlinNoise 实例自动推断采样格点（起点/步长/层数） |
| 区域批量预取（mode22） | ✅ v0.11.0 | 一次 draw 算一整列，只回读实际用到的子矩形，按 tick 限额 |
| 抽检门禁 / 配置自修复 | ✅ v0.11.0 | 整批抽检失败=永久 CPU fallback；截断 JSON 自动备份重建 |
| 满带宽批量预取（128 列/次 draw） | ✅ v0.13.0 | 纹理利用率 1.5% → 100%，同样帧预算下供给提升约 60 倍 |
| y 窗口按需扩容 / 学习重采样 | ✅ v0.13.0 | 越界自动扩窗（清缓存重铺）；采样窗太早推不出步长时换窗重试（最多 4 次） |
| 多实例槽位 | ✅ v0.13.0 | 默认 4 个实例可服务（`maxServedInstances`，1~6 可调） |

## 技术路线

- 用 Java **FFM（Foreign Function & Memory）** 直接 `dlopen` 手机上的 `libmobileglues.so`，
  `bind` 导出 GLES 原生函数，在游戏现有 EGL context 内执行。
- **不新建 Vulkan/GL 实例**、不引入额外 native 库，完全复用游戏渲染上下文。
- fragment shader 用 **double-double（DD）模拟双精度** 逐像素计算 MC PerlinNoise，
  经 RGBA32F FBO `glReadPixels` 回读，再与 CPU 端 MC 原算法逐点比对。
- mixin 挂载真实 `PerlinNoise.get(DDD)F`，用**反射**提取父类字段（perms/offsetX/Y/Z），
  每个实例在 `ShadowShared` 注册一次，之后只用 `Instance.offer(x,y,z)` 查缓存：命中则
  `setReturnValue + cancel`（真正跳过 CPU），未命中走原方法。

### v0.11.0 接管架构（为什么和 v0.10 不一样）

v0.10.x 把「噪声查询坐标直接 floor 成整数柱号」当缓存键、异步预生成 16×16×384 整数柱补丁，
真机实测命中率 **0.0%**（833600 次调用 / 0 hit）。原因是 MC 的 3D 噪声按**格点**采样：

- 坐标 = 方块坐标 × `xzScale`/`yScale`（如 `cavity` 为 1.0/0.6667、base noise 为 0.25/0.125），
  y 步长常常不是 1，柱键根本对不上；
- 区块初次生成是**一次性爆发**（日志：8 秒 83 万次调用），异步补丁预生成永远追不上；
- 旧 `pending` 队列超过 256 直接 `clear()`，丢掉全部请求。

v0.11.0 改成三件事：

1. **格点学习**：实例注册后先统计前 ~600 次真实查询，用「最小正差 + 全样本整数倍校验」推断
   格点 `x = bx + qx·dx`、`y = by + qy·dy`、`z = bz + qz·dz` 与层数 `nY`；混合尺度（同一实例被
   多个 scale 引用）推断失败即降级，不猜。y 恒定的 **2D 噪声**（MC 的 `get(DD)` 走
   `get(wrap(x),0,wrap(z))`）按单层柱接管，步长记为 1、层数 1。
2. **区域批量预取**：`mode22` 一次 draw 覆盖「多列 × 全部 y 层」，只回读实际用到的
   `count × nY` 子矩形；矩形区域（默认目标 48 批）以最近一次 miss 的格点为锚点铺开，
   每 tick 限额若干批，稳定后可覆盖上千柱。offset 折进 Java 侧的 double 基坐标再拆 dd，
   与 MC 的 double offset 完全一致。
3. **抽检门禁**：每批回读后逐柱抽首/中/尾三层与 CPU 的 MC 精确参考比对（<1e-5），
   通过的柱才写入缓存；整批全失败 → 该实例永久 CPU fallback 并释放槽位。

> 因此接管率是**真实可测**的：`[GPUWorldGen] GPU 接管 v0.11: 提供噪声 XX%` 直接反映
> 有多少次噪声查询由 GPU 提供。格点外查询（分数坐标、混合尺度）一律 CPU，绝不返回错值。

**已判死路线（勿重复尝试）**：OpenCL（Android linker namespace 拦截，需 root）、
Vulkan 第二实例（必崩）、LWJGL desktop compute（stub）、LWJGL GLES15 包（classpath 不存在）、
FFM SSBO（索引全 -1）。

## 支持环境

- Minecraft 26.3 / Fabric Loader 0.20.1 / Java 25
- 渲染后端：**OpenGLES / MobileGL**（MobileGlues 2.x），GLES 3.2
- 测试真机：HONOR AAK-AN00（骁龙 8 Elite / Adreno 830）

## 构建

```bash
export JAVA_HOME=/path/to/jdk-25
export LANG=C.utf8 LC_ALL=C.utf8     # 中文项目路径下必须，否则 Gradle 报 locale 错误
/path/to/gradle-9.8.0/bin/gradle build --no-daemon --console=plain
# 产物: build/libs/c2me-gpuworldgen-<version>.jar
```

需要：JDK 25、Gradle 9.8+、Loom 1.19-SNAPSHOT。

## 安装

1. 渲染后端选 OpenGLES / MobileGL（**不要** DirectVulkan）
2. 把 jar 放进 `mods/`
3. 配置文件 `config/gpuworldgen.json`（可选，默认 enabled）。
   若上次被强杀导致 JSON 截断，v0.11.0 会自动把坏文件备份成 `gpuworldgen.json.bak` 并重建默认配置
   （旧版只会报 `MalformedJsonException` 然后每次启动都失败）；写配置改为 tmp + 原子替换。
4. 进游戏 → 进世界，自动跑 GPU 验证，报告写 `<游戏目录>/gpu-worldgen-verify.txt`

## 验证输出

进世界后自动执行（**自检拆成每 tick 一轮**，不再一次性阻塞整帧，且先让区域预取铺缓存）：
- GL 5 轮稳定性验证（每轮重新编译 shader + 自检）
- MC 精确噪声对拍（mode 20，16384 点，超大坐标 5e6）
- 接管统计（每 40 tick 一次）：

```
[GPUWorldGen] GPU 接管 v0.11: 提供噪声 37.42% (6860 hit / 18330 调用, 格点外 12) | 实例 5 注册 / 2 可服务 | 缓存柱=3120 | 预取 180 批 / 失败 0 批
[GPUWorldGen] 区域预取耗时: 均 0.42 ms/批 (draw+read+抽检), 均 41.0 柱/批
[GPUWorldGen]   实例#1: 可服务 | 格点 x=0/4 y=-512/64(49层) z=0/4 | hit=6100 miss=2100 越界=0 格点外=0 缓存柱=1849
```

## 配置项（v0.11.0）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `size` | 128 | 固定 128（shader 网格与对拍网格一致，改别的值会被忽略） |
| `maxServedInstances` | 2 | 同时接管的噪声实例上限（多余的降级 CPU） |
| `prefetchPassesPerTick` | 6 | 每 tick 最多跑几批预取（控制帧耗时） |
| `prefetchPointsPerPass` | 2048 | 每批最多算多少格点 |
| `prefetchMaxPasses` | 48 | 一个区域的目标批数（决定区域半径） |
| `cacheBudgetMb` | 24 | 每个实例的柱缓存预算 |
| `verifyDelayTicks` | 40 | 进世界后延迟多少 tick 开始自检 |
| `writeReport` | true | 是否写 `gpu-worldgen-verify.txt` |

## v0.13.0 变更与测试方法

### 这版改了什么

- **预取带宽打满**：v0.12 每次 draw 只塞 2 列（128 宽纹理用掉 1.5%），v0.13 一次塞满 128 列，
  并拆成「每 tick 一个 y 片」推进——同样的帧预算下供给列数约 60 倍。
- **y 窗口按需扩容**：查询 y 超出学习窗口时自动扩窗并重铺缓存（v0.12 这部分约占 16% 的无效 miss）。
- **学习重采样**：前 600 次调用若坐标维度太单一（例如只沿一列采样）推不出步长，
  换一个采样窗重试，最多 4 次（v0.12 会把这些实例一次性判死）。
- **接管槽位默认 2 → 4**：`maxServedInstances` 可调（1~6）。
- **诊断增强**：每个可服务实例打印相邻调用差分样本、`maxQy`、扩容次数、实时 y 需求区间。
- 修掉验证报告抬头里硬编码的 v0.10.1。

### 测试方法（进游戏后看这几行）

1. 进世界约 30 秒后，日志里应出现 `实例#N 区域预取开始` 与 `批完成`：
   `批完成: qx=[a..b] qz=… 列=128 片=K 安装=… 失败=0` 说明 128 列批次已在跑。
2. 每 40 tick 一条统计：
   `GPU 接管 v0.13: 提供噪声 X% (hit / 调用, 格点外 …) | 实例 N 注册 / M 可服务 | 缓存柱=… | 预取 … 批 / 失败 …`
   —— 重点看 **X%** 是否比上一版（0.20%）明显上升，以及 **失败批** 是否保持 0。
3. `区域预取耗时: 均 a ms/片` 应保持在 1~3 ms 量级（v0.12 是 8.3 ms/批却只出 2 列）。
4. 逐实例行里 `越界` 应随 `扩容` 出现后不再快速增长；`格点外` 应只占很小比例。
5. 跑图 3~5 分钟，确认没有崩溃、没有地形异常（错值会被抽检挡掉，若整批不一致会打印
   `降级为 CPU fallback`，此时帧率会回落但世界不会坏）。
6. 需要反馈时给完整日志链接即可（`latest.log` 全量）。

## 后续计划

- v0.14+：按实例查询热度动态调整预算；多线程预取（渲染线程外做回读 CPU 侧比对）；批量接管 `addToVolume` 体素路径
- 远期：Vulkan 后端支持（接口已预留）
- 后面版本计划26.x

## 交付位置（约定）

每次构建出新版模组本体后，除保留 `build/libs/` 外，还要复制到手机共享存储：

`/storage/emulated/0/M1B-GPUWorldGen项目/<jar 名>`

可用通道（直接从容器写该目录会被系统拒绝 EPERM）：
`/app/export?path=…` 先落到 `Download/DSHA/`，再用 `adb-shell cp` 移入目标目录，
最后 `adb-shell sha256sum` 与本地校验和逐字节核对。

## 协议

<div align="center">Mod 采用 MIT 协议</div>

<div align="center">© 2026 ZQ-斩情</div>

---

**免责声明**：本项目为独立 mod，引用 MC 类名用于适配，不打包分发 Minecraft 本体，本项目基本由AI编写代码
