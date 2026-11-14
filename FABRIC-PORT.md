# Carbon 26.1.2 Fabric 移植说明

本分支由上游 `26.1.2-neoforge`（0.9-B）移植至 Minecraft 26.1.2 / Fabric。渲染核心、着色器与 Iris 桥接沿用上游 26.1.2 代码，加载器部分按 Fabric 接口改写。

## 环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | 26.1.2（26.x 不再混淆，运行时即 Mojang 名称） |
| Fabric Loader | ≥ 0.19.3（构建使用 0.20.1） |
| Fabric API | 构建使用 0.155.3+26.1.2 |
| Java | 25 |
| 构建 | Fabric Loom 1.19.4（`net.fabricmc.fabric-loom`，不重映射）· Gradle 9.8.2 |
| 可选 | Mod Menu 20（配置界面）· Iris 1.11.x · Sodium 0.9.x |

构建：`gradlew build`，输出 `build/libs/carbon-26.1.2.9-B.2-fabric.jar`。

## NeoForge 26.1.2 → Fabric 26.1.2 对照

| 26.1.2 NeoForge | 26.1.2 Fabric 实现 |
| --- | --- |
| `@Mod` 构造函数、`ModConfigSpec`、`ConfigurationScreen` | `ClientModInitializer`，自带同格式 `carbon-client.toml` 读写，Mod Menu 与 `/carbon config` 配置界面 |
| `AddClientReloadListenersEvent` / `LoggingOut` / `GameShuttingDownEvent` | `ResourceLoader`（resource-loader v1）、`ClientPlayConnectionEvents.DISCONNECT`、`ClientLifecycleEvents.CLIENT_STOPPING`；释放 GL 资源时切回渲染线程 |
| `RenderLevelStageEvent.AfterOpaqueFeatures` | `LevelRenderEvents.AFTER_SOLID_FEATURES` |
| `RegisterClientCommandsEvent` | `ClientCommandRegistrationCallback` + `ClientCommands` |
| `IClientItemExtensions` | 无等价接口，固定原版散布与浮动 |
| `neoforge:core/item_unlit` 着色器 | Fabric 无此变体，只接受原版 `core/item` |

## 26.2 → 26.1.2 回移差异

26.2 分支在 26.1.2 代码基础上引入的接口改动，本分支已逐项还原：

| 26.2 | 26.1.2 实现 |
| --- | --- |
| 特性渲染拆为准备与执行两段：`FeatureRenderDispatcher` 先 `buildGroup` 写入共享顶点缓冲，再 `executeGroup`；提交类型为 `ItemFeatureRenderer.Submit` / `ShadowFeatureRenderer.Submit` | 立即绘制：`ItemFeatureRenderer.renderSolid` 与 `ShadowFeatureRenderer.renderTranslucent` 遍历 `SubmitNodeCollection` 的提交逐个绘制，提交类型为 `SubmitNodeStorage.ItemSubmit` / `ShadowSubmit`。Carbon 在遍历前用 `@WrapOperation` 替换 `getItemSubmits()` / `getShadowSubmits()` 的返回值移出可实例化提交，在该批剩余提交绘制完成后（`TAIL`）实例化绘制 |
| `FeatureRenderDispatcher$PreparedFrame` 的 `begin` / `executeSolid` / `close` 用于帧计数 | `renderAllFeatures` 前后计入特性帧深度，`renderSolidFeatures` 开头补绘掉落物的光影阴影 |
| `LevelExtractor.extractVisibleEntities` | `LevelRenderer.extractVisibleEntities` |
| `RenderType.prepare()` 返回 `PreparedRenderType`（管线、动态变换、裁剪、纹理） | 从 `RenderType` 内部 `RenderSetup` 读取纹理绑定与纹理变换（两个访问器 Mixin），动态变换由 `RenderSystem.getModelViewMatrix()` 与 `DynamicUniforms.writeTransform` 组合，裁剪取 `RenderSystem.getScissorStateForRenderTypeDraws()` |
| `VertexFormatElement(name, offset, GpuFormat)`、`PrimitiveTopology` | `VertexFormatElement(id, index, Type, normalized, count)` 与 `VertexFormat.Mode`；属性名取 `VertexFormat.getElementName`，偏移取 `getOffset` |
| `createRenderPass(..., Optional<Vector4fc>, ...)`、`drawIndexed(count, instances, firstIndex, vertexOffset, firstInstance)`、`setVertexBuffer(int, GpuBufferSlice)` | `OptionalInt`、`drawIndexed(count, instances, firstIndex, vertexOffset)`、`setVertexBuffer(int, GpuBuffer)` |
| `Minecraft.getMainRenderTarget()`、`gui.hud.getGuiTicks()`、`gui.setScreen` | `Minecraft.getMainRenderTarget()`、`gui.getGuiTicks()`、`minecraft.setScreen` |
| `TextureTransform.createMatrix()`、`ItemTags.COMPASSES`、`EntityTypes.ITEM` | `TextureTransform.getMatrix()`、`Items.COMPASS`/`Items.CLOCK`、`EntityType.ITEM` |
| 反向深度（近处深度为 1），独立绘制 `GL_GEQUAL` | 常规深度，独立绘制 `GL_LEQUAL` |
| Vulkan 后端与 RenderPipeline 实例化路径（`B3d`），OpenGL 下可用 `-Dcarbon.blaze3d` 强制 | 26.1.2 只有 OpenGL 后端，移除该路径及其着色器、`DynamicUniforms` 轮换注入 |

## 实测

`gradlew prodClient -PsmokeTest`：Fabric API 0.155.3、Sodium 0.9.2、Iris 1.11.4、Mod Menu 18.0.2。超平坦世界放置 400 个掉落物，默认 250 个实例化为 600 个实例、6 次绘制，250 个圆形阴影 1 次绘制；界面与手持物品走通用路径实例化。玻璃与白色染色玻璃（半透明材质）保持原版，指南针等条件模型未接管。默认、物理化、关闭与重新开启四个阶段均输出截图，帧率 117 / 119 / 118 FPS，无崩溃与 Mixin 错误。

## 已知问题与未验证项

- 未在真实启动器与大型整合包中测试。
- Iris 联动继承上游实现，本次冒烟测试未加载光影包，光影路径下的实例化未复测。
- AMD 显卡未验证。
