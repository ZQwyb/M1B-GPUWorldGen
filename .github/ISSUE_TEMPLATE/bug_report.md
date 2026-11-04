---
name: Bug 报告
about: 崩溃、花屏、地形异常、接管统计不对
title: '[bug] '
labels: bug
assignees: ''
---

## 环境

| 项 | 值 |
| --- | --- |
| 模组版本 | （如 v0.16.0，见 `latest.log` 里的 `mod 加载 v…`） |
| 手机型号 / 芯片 | （如 HONOR AAK-AN00 / 骁龙 8 Elite） |
| 启动器 | （Zalith / Pojav 等 + 版本） |
| 渲染后端 | OpenGLES / MobileGL(MobileGlues 版本) |
| MC / Fabric / Java | 26.3 / 0.20.1 / 25 |

## 现象

- [ ] 崩溃（闪退）
- [ ] 花屏 / 画面异常
- [ ] 地形异常（断层、空洞、方块错位）
- [ ] 帧率 / 卡顿
- [ ] 统计数字不对（如命中率恒为 0）

具体描述：

## 日志

**请附完整 `latest.log` 的链接**（https://mclo.gs/ 或 https://logshare.cn/ 都可以，
不要只贴片段）。如果崩溃，同时附 `crash-report` 或日志末端的 `Java Exit code` 部分。

其中这几行最关键，请确认在日志里出现过：

```
[GPUWorldGen] mod 加载 v…
[GPUWorldGen][mixin] 已命中 get(DDD)F，首次调用 (x=… y=… z=…)
[GPUWorldGen] GPU 接管 v…: 提供噪声 …% (… hit / … 调用, 格点外 …) | 实例 … 注册 / … 可服务
[GPUWorldGen][B探针] addToVolume 首次: …
```

## 复现步骤

1.
2.
3.

## 已尝试

（例如：关掉 `gpuTakeover` 后是否还崩；换 0.15.4 是否正常）
