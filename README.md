# Carbon（碳）

客户端 GPU 物品模型渲染优化模组，本分支适用于 Minecraft 1.21.1 / Fabric Loader 0.16 及以上，需要 Fabric API，使用 Java 21，MIT 许可证。NeoForge 版本见 `master` 分支，移植对照与已知问题见 [Fabric 移植说明](FABRIC-PORT.md)。

![Carbon](branding/mod-cover.svg)

**下载：** [Modrinth](https://modrinth.com/mod/carbon-for-fabric) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/carbon-for-fabric) · [GitHub Releases](https://github.com/khiqwq/Carbon/releases)

缓存重复物品的模型几何，通过 GPU 实例化合并绘制。默认使用掉落物专用管线，在 GPU 计算自旋、浮动与缩放，并复用未变化的场景记录；普通附魔光效与原版圆形阴影同样参与实例化。

标准 `ItemRenderer.renderModelLists` 提交也可优化，覆盖手持、界面、展示和其他模组调用标准物品模型接口的场景。通用路径保留调用方的姿态、光照与叠加参数。无法保持排序或特殊顶点语义的情况交还原有渲染。

可选物理旋转使用 ItemPhysicLite 的旋转组合顺序，有实际位移时绕模型局部 Y 轴翻滚；平面物品落地立即平放，立体物品保留着地朝向并停止翻滚。每个实体使用固定的微小高度偏移，减轻重叠表面的闪烁。仅影响视觉，不改变实体速度、碰撞或拾取。

## 配置

配置文件为 `config/carbon-client.toml`，安装 Mod Menu 后可在模组列表打开配置界面，提供中文与英文名称、提示。

```toml
enabled = true
optimizeDroppedItems = true
physicalDroppedItems = false
skipVisibilityChecks = true
cacheUpdateBudgetMs = 2.0
```

- `enabled`：优化总开关。
- `optimizeDroppedItems`：默认启用掉落物专用 GPU 管线；关闭后保留其他模组的掉落物变换，标准模型接口继续参与通用实例化。
- `physicalDroppedItems`：默认关闭的物理旋转视觉效果，需要开启专用管线。OpenGL 4.3 下缓存静止变换，旧设备使用顶点着色器路径。
- `skipVisibilityChecks`：默认开启，恢复 0.7 的行为，跳过视锥与遮挡检查，提交距离内的普通掉落物，包括摄像机后的物品；仅转向时复用场景。关闭后恢复完整可见性检查，渲染距离限制始终有效。
- `cacheUpdateBudgetMs`：过期的掉落物缓存排队分帧更新，每帧默认分配 2 毫秒，可设为 0–20 毫秒，0 表示当帧立即完成。队列完成前继续绘制上一份场景；预算仅限制缓存更新，不限制整帧耗时，单个复杂物品的更新可能超出预算。

`/carbon config` 打开配置界面，`/carbon enabled false` 关闭优化，`/carbon stats` 查看绘制和缓存统计。升级时替换旧的 Item Model Optimization Jar；新配置尚不存在时会迁移旧客户端配置。

## 光影兼容

Iris 访问集中在 `IrisCompatibility`，无需向 Iris 类注入 Mixin，也不修改其原生着色器程序。Carbon 保留 Iris 的扩展顶点、材质标识和渲染目标，为自己的绘制构建实例化变体，支持主渲染、光影阴影和管线重载。

NeoForge 分支已验证 Iris `1.8.14-beta.1+mc1.21.1` 与 Photon `v1.4`；Fabric 分支已验证 Iris `1.8.14-beta.1+1.21.1-fabric` 与 Photon `v1.4`，包括光影阴影。光影的原生阴影由 Iris 管理，特殊通道在需要时保留原生提交。

## 验证与构建

[更新记录](CHANGELOG.md) · [SVG 封面](branding/mod-cover.svg)

使用 Java 21，执行 `gradlew.bat build`，输出 `build/libs/carbon-1.21.1.9-B-fabric.jar`。`gradlew.bat prodClient -PsmokeTest` 以生产环境 Jar 启动带 Sodium、Iris 与 Mod Menu 的客户端，并运行自动掉落物冒烟测试；去掉 `-PsmokeTest` 则手动测试。

## 分支与致谢

| 分支 | Minecraft | 运行环境 | 许可证 |
| --- | --- | --- | --- |
| [`1.21.11`](https://github.com/khiqwq/Carbon/tree/1.21.11) | 1.21.11 | Fabric Loader 0.18+，Java 21 | MIT |
| [`1.21.1`](https://github.com/khiqwq/Carbon/tree/1.21.1) | 1.21.1 | Fabric Loader 0.16+，Java 21 | MIT |
| [`1.20.1`](https://github.com/khiqwq/Carbon/tree/1.20.1) | 1.20.1 | Fabric Loader 0.15+，Java 17 | GPL-3.0 |
| [`1.17.1`](https://github.com/khiqwq/Carbon/tree/1.17.1) | 1.17.1 | Fabric Loader 0.15+，Java 16+ | GPL-3.0 |

本项目是 [CHUJIAN-afk/Carbon](https://github.com/CHUJIAN-afk/Carbon)（FirstSight，MIT）的非官方 Fabric 移植。1.20.1 与 1.17.1 分支基于 Colin_101 的 [Carbon-for-Fabric](https://github.com/Colin1456/Carbon-for-Fabric)，沿用其 GPL-3.0 许可证，上游代码的 MIT 声明保留在 `LICENSE-MIT`。
