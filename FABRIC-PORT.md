# Carbon 1.21.1 Fabric 移植说明

本分支由 `master`（1.21.1 NeoForge，1.21.1.9-A）移植，渲染核心、着色器与 Iris 桥接代码保持一致，仅替换加载器相关部分。

## 环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.16.0（构建使用 0.16.14） |
| Fabric API | 构建使用 0.116.7+1.21.1，运行时任意 1.21.1 版本 |
| Java | 21 |
| 映射 | Mojang 官方映射 |
| 构建 | Fabric Loom 1.16.1 · Gradle 9.6.1 |
| 可选 | Mod Menu 11（配置界面）· Iris 1.8.x（光影）· Sodium 0.8.x |

构建：`gradlew build`，输出 `build/libs/carbon-1.21.1.9-B-fabric.jar`。

## NeoForge → Fabric 对照

| NeoForge | Fabric 实现 |
| --- | --- |
| `@Mod` 构造函数 | `ClientModInitializer`（`fabric.mod.json` 的 `client` 入口） |
| `ModConfigSpec` / `carbon-client.toml` | 自带的同名同格式 TOML 读写（`Carbon.Settings`），旧配置迁移逻辑保留 |
| `ConfigurationScreen` | 原版控件编写的 `ConfigScreen`，通过 Mod Menu 入口打开 |
| `RegisterClientReloadListenersEvent` | `ResourceManagerHelper` 同步重载监听器 |
| `ClientPlayerNetworkEvent.LoggingOut` | `ClientPlayConnectionEvents.DISCONNECT` |
| `GameShuttingDownEvent` | `ClientLifecycleEvents.CLIENT_STOPPING` |
| `RenderFrameEvent.Pre` | `GameRendererMixin`：`GameRenderer.render` 头部 |
| `RenderLevelStageEvent.AFTER_ENTITIES` | `WorldRenderEvents.AFTER_ENTITIES`（同为实体循环与实体批次提交之后、方块实体之前） |
| `RenderLevelStageEvent.AFTER_LEVEL` | `WorldRenderEvents.END` |
| `RegisterClientCommandsEvent` | `ClientCommandRegistrationCallback`（`/carbon enabled`、`/carbon stats`） |
| `IClientItemExtensions.shouldSpreadAsEntity / shouldBobAsEntity` | 无等价接口，固定为原版行为（散布、浮动） |
| `BakedModel.getRenderPasses` / `getRenderTypes` | 单一渲染通道 + `ItemBlockRenderTypes.getRenderType(stack, true)` |
| `LevelRenderer.getFrustum()` | 访问加宽 `capturedFrustum` / `cullingFrustum`，按 NeoForge 逻辑取值 |
| `ItemOverrides.getOverrides()` | 访问加宽 `overrides` 数组 |
| `SpriteContents.getOriginalImage()` | 访问加宽 `originalImage` 字段 |
| AT 公开的 `ItemRenderer.renderModelLists` | 访问加宽 |
| NeoForge 早期窗口创建的 OpenGL 4.6 上下文 | `WindowMixin`：依次请求 4.6 → 3.3 核心上下文，失败回到原版 3.2 |

原有 14 个 Mixin 的目标方法在原版与 NeoForge 中签名一致，直接沿用；新增 `GameRendererMixin` 与 `WindowMixin`。

## 实测

测试硬件：NVIDIA GeForce RTX 5070 Laptop GPU，驱动 610.74，OpenGL 4.6。生产环境 Jar（`gradlew prodClient -PsmokeTest`），模组组合 Carbon + Fabric API 0.116.7 + Sodium 0.8.13 + Iris 1.8.14-beta.1（未加载光影包）+ Mod Menu 11.0.3。冒烟测试模组（`src/smoketest`，不进入发布 Jar）创建平坦世界，生成 400 个掉落物并在物品栏放入 9 个物品。

| 场景 | 结果 |
| --- | --- |
| 含指南针、附魔书、染色玻璃的混合场景 | 349 个实体实例化为 695 个实例、约 31–34 次绘制；圆形阴影 349 个合为 1 次绘制；染色玻璃按设计回退原版 |
| 仅静态物品 | 400 个实体、900 个实例合为 8 次绘制，阴影 1 次绘制；807 次场景复用 / 28 次重建（物理模式 703 / 1） |
| 物理旋转开启 | 正常运行，平面物品平放；截图检查无错位 |
| 通用模型路径（界面与手持） | 9 次模型提交实例化为 10 次绘制，Carbon 先于 Sodium 的物品快速路径执行 |
| 运行中关闭 / 重新开启 | 统计归零后恢复，无错误 |
| Iris + Photon v1.4 光影 | Iris 桥接启用且未阻断；掉落物 349 个实体合为约 32 次绘制，Iris 派生程序每帧约 63 次实例化绘制（含光影阴影通道的 616 次通用模型提交）；截图无错位。帧率上限约 120 时，开启 115 FPS，关闭 91 FPS |

## 可能的问题

1. **OpenGL 上下文版本。** 原版 1.21.1 只请求 OpenGL 3.2 核心上下文，NVIDIA 驱动会精确返回 3.2，Carbon 因缺少 3.3 而整体停用。NeoForge 的早期窗口会请求最高版本，Fabric 没有对应机制，因此新增 `WindowMixin` 提升上下文版本。其他同样修改窗口创建的模组若使用 `@Redirect` 替换 `glfwCreateWindow` 会与之冲突；如遇窗口创建问题，可使用 JVM 参数 `-Dcarbon.vanillaContext=true` 恢复原版行为（此时 Carbon 在只提供 3.2 的驱动上不生效）。macOS 最高为 4.1，计算着色器路径不可用，回退顶点着色器路径，与 NeoForge 一致。
2. **物品客户端扩展。** Fabric 没有 `IClientItemExtensions`，所有物品均按原版散布与浮动处理。通过 Mixin 修改 `ItemEntityRenderer` 的 Fabric 模组可能被专用管线绕过，可关闭 `optimizeDroppedItems` 恢复。
3. **多渲染通道与 Fabric Renderer API。** NeoForge 的多通道 / 多渲染类型模型在 Fabric 不存在。仅类为 `SimpleBakedModel` 的模型参与缓存，Fabric Renderer API（FRAPI）模型、Continuity 等包装模型自动回退原生渲染，不会出错但不会被优化。
4. **Iris 光影包。** Iris 桥接全部通过反射完成，Fabric 版 Iris 与 NeoForge 版类名一致。已用 Photon v1.4 实测主渲染、光影阴影通道与开关切换（见实测表），其他光影包未测试。Iris 在 Mojang 映射开发环境中会因自身 refmap 失败而崩溃，带 Iris 的测试需使用 `prodClient`。
5. **Sodium 物品快速路径。** Sodium 同样在 `ItemRenderer.renderModelLists` 头部注入并取消。实测 Carbon 先执行；若 Mixin 应用顺序因其他模组改变，通用路径会静默回退到 Sodium 处理，掉落物专用管线不受影响。
6. **其他渲染模组。** ImmediatelyFast、EntityCulling、Entity Model Features 等 Fabric 常见模组未测试。ImmediatelyFast 会改动 `BufferSource` 批处理，可能影响 `BufferSourceMixin` 的批次刷新时机。NeoForge 版本针对 Chloride 的实体筛选兼容在 Fabric 中无对应模组。
7. **配置文件。** 使用自带的简化 TOML 读写，只识别 `键 = 值` 行；无法解析的条目回退默认值并在日志中警告，文件在启动时按标准格式重写。
8. **生产环境反射名称。** NeoForge 运行时使用 Mojang 名称，Fabric 生产环境使用 intermediary 名称。原代码 `Item.class.getMethod("isFoil", …)` 在 Fabric 生产环境找不到方法，所有物品被视为动态光效，场景只能在同一 tick 内复用；已改用 `MappingResolver` 解析 `method_7886`。
9. **缓存失败的模型。** 构建网格失败（例如含半透明像素需原版排序）的模型现在记入拒绝列表，不再逐帧重试并重复输出警告；原分支同一情况下每帧重试，测试场景中一分钟内产生约 25 万行日志。
