# Carbon（碳）

Minecraft 26.3 / Fabric Loader 0.19.3 及以上（需要 Fabric API）客户端 GPU 物品模型渲染优化模组，Java 25，MIT 许可证。本分支由 26.2 分支（上游 `26.1.2-neoforge` 的 Fabric 移植）移植，移植对照、实测与已知问题见 [Fabric 移植说明](FABRIC-PORT.md)。支持 OpenGL 与 Vulkan 渲染后端。

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
- `optimizeDroppedItems`：默认开启掉落物专用 GPU 管线；关闭后保留原有掉落物变换，通过标准模型提交继续优化。检测到其他模组改写掉落物（antideath-survival-assistant 的禁止旋转、TweakerMore 的精确物品模型、Meteor Client 的 ItemPhysics 与隐藏掉落物）时自动让位。
- `physicalDroppedItems`：默认关闭，需要开启掉落物专用管线；落地姿态收稳后停止派发变换计算。
- `skipVisibilityChecks`：专用管线默认跳过遮挡与视锥检查，完整提交距离内的普通掉落物；距离限制始终有效。

- `cacheUpdateBudgetMs`：每帧缓存更新预算，默认 2 毫秒，可设为 0–20 毫秒，0 表示立即完成；队列完成前绘制旧实例与阴影。单个物品更新可能超预算。

`/carbon config` 打开配置界面，`/carbon enabled false` 停用优化，`/carbon stats` 查看统计。新配置尚不存在时会迁移旧客户端配置；升级时替换旧 Jar。

## 渲染后端

26.3 的特性渲染在原版打开的渲染通道中执行，OpenGL 与 Vulkan 都经 RenderPipeline 绘制：掉落物、物理化掉落物与通用物品提交各用一条实例化管线，附魔光效与原版一样在同一次绘制中叠加。实例数据作为逐实例步进的顶点绑定上传，自旋、浮动与翻滚在顶点着色器中计算；实例、uniform 与纹理在准备阶段写好，执行阶段只绑定与绘制。掉落物圆形阴影同样实例化，通用物品的阴影交给原版。

## 光影

启用 Iris 光影包时 Carbon 保持原版物品渲染：26.3 的 OpenGL 后端改为共享渲染通道，26.2 分支中为 Iris 程序派生实例化变体的桥接尚未移植。已验证 Iris 1.11.7 与 Photon v1.4 下物品显示正常；光影只在 OpenGL 下测试。

## 验证与构建

[更新记录](CHANGELOG.md)。使用 Java 25 执行 `gradlew.bat build`，输出 `build/libs/carbon-26.3.9-B-fabric.jar`。`gradlew.bat prodClient -PsmokeTest` 以生产环境 Jar 运行冒烟测试，追加 `"-PprodArgs=--graphicsBackend vulkan"` 时使用 Vulkan 后端，加 `-PsmokeStress=60` 反复切换配置以检验 JIT 重新编译；`gradlew.bat benchPack -PbenchDir=<目录>` 在整合包隔离副本中对比帧率（整合包的模组与配置需预先复制到该目录）。

## 分支与致谢

| 分支 | Minecraft | 运行环境 | 许可证 |
| --- | --- | --- | --- |
| [`26.3`](https://github.com/khiqwq/Carbon/tree/26.3) | 26.3 | Fabric Loader 0.19.3+，Java 25 | MIT |
| [`26.2`](https://github.com/khiqwq/Carbon/tree/26.2) | 26.2 | Fabric Loader 0.19.3+，Java 25 | MIT |
| [`1.21.11`](https://github.com/khiqwq/Carbon/tree/1.21.11) | 1.21.11 | Fabric Loader 0.18+，Java 21 | MIT |
| [`1.21.1`](https://github.com/khiqwq/Carbon/tree/1.21.1) | 1.21.1 | Fabric Loader 0.16+，Java 21 | MIT |
| [`1.20.1`](https://github.com/khiqwq/Carbon/tree/1.20.1) | 1.20.1 | Fabric Loader 0.15+，Java 17 | GPL-3.0 |
| [`1.17.1`](https://github.com/khiqwq/Carbon/tree/1.17.1) | 1.17.1 | Fabric Loader 0.15+，Java 16+ | GPL-3.0 |

本项目是 [CHUJIAN-afk/Carbon](https://github.com/CHUJIAN-afk/Carbon)（FirstSight，MIT）的非官方 Fabric 移植。1.20.1 与 1.17.1 分支基于 Colin_101 的 [Carbon-for-Fabric](https://github.com/Colin1456/Carbon-for-Fabric)，沿用其 GPL-3.0 许可证，上游代码的 MIT 声明保留在 `LICENSE-MIT`。
