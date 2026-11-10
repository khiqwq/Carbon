# Carbon（碳）

Minecraft 1.21.11 / Fabric Loader 0.18 及以上（需要 Fabric API）客户端 GPU 物品模型渲染优化模组，Java 21，MIT 许可证。本分支由 `26.1.2-neoforge` 回移，移植对照、实测与已知问题见 [Fabric 移植说明](FABRIC-PORT.md)。

![Carbon](branding/mod-cover.svg)

**下载：** [Modrinth](https://modrinth.com/mod/carbon-for-fabric) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/carbon-for-fabric/preview) · [GitHub Releases](https://github.com/khiqwq/Carbon/releases)

缓存重复物品的模型几何，通过 GPU 实例化合并绘制。原生 `ItemFeatureRenderer` 的物品提交保留调用者的变换、光照和叠加参数，覆盖掉落物、手持、界面及其他调用标准接口的渲染。特殊投影光效、自定义材质和需排序的半透明模型保留原生处理。

默认启用掉落物专用管线，在 GPU 计算自旋、浮动和缩放，复用静止场景记录；普通附魔光效与地面阴影同样参与实例化。可选物理化沿模型局部轴翻滚，停止后平滑躺平，并加入每个实体固定的微小高度偏移。物理效果仅改变视觉，不修改速度、碰撞或拾取。

## 配置

配置文件为 `config/carbon-client.toml`。安装 Mod Menu 后可打开配置界面，提供中英文名称与提示。

```toml
enabled = true
optimizeDroppedItems = true
physicalDroppedItems = false
skipVisibilityChecks = true
cacheUpdateBudgetMs = 2.0
```

- `enabled`：优化总开关。
- `optimizeDroppedItems`：默认开启掉落物专用 GPU 管线；关闭后保留原有掉落物变换，通过标准模型提交继续优化。
- `physicalDroppedItems`：默认关闭，需要开启掉落物专用管线；落地姿态收稳后停止派发变换计算。
- `skipVisibilityChecks`：专用管线默认跳过遮挡与视锥检查，完整提交距离内的普通掉落物；距离限制始终有效。

- `cacheUpdateBudgetMs`：每帧缓存更新预算，默认 2 毫秒，可设为 0–20 毫秒，0 表示立即完成；队列完成前绘制旧实例与阴影。单个物品更新可能超预算。

`/carbon config` 打开配置界面，`/carbon enabled false` 停用优化，`/carbon stats` 查看统计。新配置尚不存在时会迁移旧客户端配置；升级时替换旧 Jar。

## 光影

Iris 检测集中在 `IrisCompatibility`，启用光影包时为 Iris 程序派生实例化变体，覆盖主通道与光影阴影。已验证 Iris 1.10.8 与 Photon v1.3b。

## 验证与构建

[更新记录](CHANGELOG.md)。使用 Java 21 执行 `gradlew.bat build`，输出 `build/libs/carbon-1.21.11.9-B-fabric.jar`。`gradlew.bat prodClient -PsmokeTest` 以生产环境 Jar 运行冒烟测试；`gradlew.bat benchPack -PbenchDir=<目录>` 在整合包隔离副本中对比帧率（整合包的模组与配置需预先复制到该目录）。

## 分支与致谢

| 分支 | Minecraft | 运行环境 | 许可证 |
| --- | --- | --- | --- |
| [`1.21.11`](https://github.com/khiqwq/Carbon/tree/1.21.11) | 1.21.11 | Fabric Loader 0.18+，Java 21 | MIT |
| [`1.21.1`](https://github.com/khiqwq/Carbon/tree/1.21.1) | 1.21.1 | Fabric Loader 0.16+，Java 21 | MIT |
| [`1.20.1`](https://github.com/khiqwq/Carbon/tree/1.20.1) | 1.20.1 | Fabric Loader 0.15+，Java 17 | GPL-3.0 |
| [`1.17.1`](https://github.com/khiqwq/Carbon/tree/1.17.1) | 1.17.1 | Fabric Loader 0.15+，Java 16+ | GPL-3.0 |

本项目是 [CHUJIAN-afk/Carbon](https://github.com/CHUJIAN-afk/Carbon)（FirstSight，MIT）的非官方 Fabric 移植。1.20.1 与 1.17.1 分支基于 Colin_101 的 [Carbon-for-Fabric](https://github.com/Colin1456/Carbon-for-Fabric)，沿用其 GPL-3.0 许可证，上游代码的 MIT 声明保留在 `LICENSE-MIT`。
