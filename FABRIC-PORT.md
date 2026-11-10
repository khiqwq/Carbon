# Carbon 26.2 Fabric 移植说明

本分支由上游 `26.1.2-neoforge`（0.9-B）移植至 Minecraft 26.2 / Fabric。渲染核心、着色器与 Iris 桥接沿用上游 26.1.2 代码，加载器部分取自本仓库的 Fabric 分支，再按 26.2 的接口改写差异处。

## 环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | 26.2（26.x 不再混淆，运行时即 Mojang 名称） |
| Fabric Loader | ≥ 0.19.3（构建使用 0.20.1） |
| Fabric API | 构建使用 0.161.0+26.2 |
| Java | 25 |
| 构建 | Fabric Loom 1.19.4（`net.fabricmc.fabric-loom`，不重映射）· Gradle 9.8.2 |
| 可选 | Mod Menu 20（配置界面）· Iris 1.11.x · Sodium 0.9.x |

构建：`gradlew build`，输出 `build/libs/carbon-26.2.9-B.2-fabric.jar`。

## NeoForge 26.1.2 → Fabric 26.2 对照

| 26.1.2 NeoForge | 26.2 Fabric 实现 |
| --- | --- |
| `@Mod` 构造函数、`ModConfigSpec`、`ConfigurationScreen` | `ClientModInitializer`，自带同格式 `carbon-client.toml` 读写，Mod Menu 与 `/carbon config` 配置界面 |
| `AddClientReloadListenersEvent` / `LoggingOut` / `GameShuttingDownEvent` | `ResourceLoader`（resource-loader v1）、`ClientPlayConnectionEvents.DISCONNECT`、`ClientLifecycleEvents.CLIENT_STOPPING`；释放 GL 资源时切回渲染线程 |
| `RenderLevelStageEvent.AfterOpaqueFeatures` | `LevelRenderEvents.AFTER_SOLID_FEATURES` |
| `RegisterClientCommandsEvent` | `ClientCommandRegistrationCallback` + `ClientCommands` |
| `IClientItemExtensions` | 无等价接口，固定原版散布与浮动 |
| `neoforge:core/item_unlit` 着色器 | Fabric 无此变体，只接受原版 `core/item` |

## 26.1.2 → 26.2 接口变化

| 26.1.2 | 26.2 |
| --- | --- |
| `ItemFeatureRenderer.renderSolid / renderTranslucent / renderItem` 逐个提交直接绘制 | 特性渲染拆为准备与执行两段：`FeatureRenderDispatcher` 先为整帧各阶段的分组调用 `buildGroup` 写入共享顶点缓冲并一次上传，再按阶段 `executeGroup`。Carbon 在物品 `buildGroup` 开头移出可实例化的提交，在 `RenderTypeFeatureRenderer.executeGroup` 末尾、该帧第一个含实例的物品分组执行时绘制 |
| `ShadowFeatureRenderer.renderTranslucent` 读取 `getShadowSubmits()` | 同样在 `buildGroup` 截取、`executeGroup` 绘制，多个分组的阴影累积后一次绘制 |
| `SubmitNodeStorage.ItemSubmit` / `ShadowSubmit` | `ItemFeatureRenderer.Submit` / `ShadowFeatureRenderer.Submit` |
| `LevelRenderer.extractVisibleEntities` | `LevelExtractor.extractVisibleEntities`（提取在渲染之前完成，早于 Iris 阴影通道） |
| `ItemFeatureRenderer.getFoilRenderType` | 按 `ItemFeatureRenderer.getFoilBuffer` 的规则选择 `RenderTypes.glint()` / `glintTranslucent()` |
| `RenderSetup.getTextures()`、`TextureTransform.getMatrix()`、`RenderSystem.getModelViewMatrix()` | Iris 绘制改用 `RenderType.prepare()` 返回的 `PreparedRenderType`（管线、动态变换、裁剪、纹理），移除两个访问器 Mixin |
| `VertexFormatElement.type() / count() / normalized()`、`VertexFormat.Mode` | `VertexFormatElement(name, offset, GpuFormat)`，按 `GpuFormat.ComponentType` 选择 GL 类型与归一化；`PrimitiveTopology` |
| `createRenderPass(..., OptionalInt, ...)`、`drawIndexed(baseVertex, firstIndex, count, instances)` | `Optional<Vector4fc>` 清除色；`drawIndexed(count, instances, firstIndex, vertexOffset, firstInstance)` |
| `Minecraft.getMainRenderTarget()`、`gui.getGuiTicks()`、`setScreen`、`GuiGraphics` | `gameRenderer.mainRenderTarget()`、`gui.hud.getGuiTicks()`、`gui.setScreen`、`GuiGraphicsExtractor.extractRenderState` |
| `Window` 构造函数创建窗口 | 静态 `Window.createGlfwWindow(..., GpuBackend)`；`WindowMixin` 只在 OpenGL 后端依次请求 4.6 → 4.0 |

## 26.2 特有问题（已处理）

1. **反向深度**：26.2 的投影矩阵交换近远平面（近处深度为 1），`DepthStencilState.DEFAULT` 改为 `GREATER_THAN_OR_EQUAL`，主通道把深度清为 0。沿用 `GL_LEQUAL` 时所有实例化物品都无法通过深度测试而不可见（掉落物、手持与物品栏图标全部消失）。独立绘制改用 `GL_GEQUAL`。
2. **Java 25.0.1 的 C2 崩溃**：官方启动器为 26.x 提供的 Java 25.0.1 在 C2 编译原先单个 920 字节、含嵌套循环与多层 try-with-resources 的 `ItemBatchRenderer.flush` 时崩溃（`EXCEPTION_ACCESS_VIOLATION`，位于 `jvm.dll`）。拆成帧设置、逐批绘制、光效状态与过期清理几个小方法后不再出现。
3. **嵌套特性帧**：Iris 在主帧准备之后、执行之前完整渲染自己的阴影帧（独立的 `FeatureRenderDispatcher`）。通用物品与阴影管线按已准备未关闭的帧计数，只在最外层帧接管；Iris 阴影帧中的手持、展示框等物品保留原生绘制。掉落物专用管线的光影阴影在阴影帧 `executeSolid` 开头补绘，主帧状态不受影响。
4. **Vulkan 后端**：26.2 新增 Vulkan 后端（默认仍优先 OpenGL，选项中可切换）。上游绘制直接调用 GL 并依赖计算着色器，blaze3d 没有计算通道，因此 Vulkan 下改用 `B3d`：实例数据作为第二条顶点绑定（逐实例步进）上传，`RenderPipeline` 组合原版的矩阵、雾效与光照片段和 Carbon 自己的 `CarbonDraw` uniform 块，自旋、浮动与物理化翻滚改在顶点着色器中计算；圆形阴影以逐顶点角点代替 `gl_VertexID`。着色器经 26.2 的 GLSL → SPIR-V 编译，两种后端共用。每帧的 uniform 存储随 `DynamicUniforms.reset` 轮换。B 与 B.1 在 Vulkan 下崩溃，原因是光影检测在判断后端之前调用了 `GL.getCapabilities()`。
5. **半透明材质**：26.2 的物品按四边形材质区分实心、镂空与半透明。玻璃等半透明材质保持原版排序绘制，与上游 26.1.2 一致。

## 实测

测试硬件：OpenGL 下为 NVIDIA GeForce RTX 5070 Laptop GPU，驱动 610.74；Java 25.0.1（与官方启动器为 26.x 提供的运行时相同）。

**渲染模组组合**（`gradlew prodClient -PsmokeTest`）：Fabric API 0.161.0、Sodium 0.9.2、Iris 1.11.4、Mod Menu 20.0.3。400 个掉落物中 250 个实例化为 600 个实例、6 次绘制，250 个圆形阴影 1 次绘制；界面与手持物品走通用路径实例化。玻璃与染色玻璃（半透明材质）及指南针（条件模型）保持原版。默认、物理化、关闭与重新开启四个阶段截图检查无错位。

**Photon v1.4 光影**：主通道 37 次 Iris 实例化绘制（698 个实例），光影阴影通道 5 次（600 个实例），截图与关闭 Carbon 时一致。

**Vulkan 后端**（Intel Graphics 核显，驱动 101.8424；本机在 Vulkan 下选用核显）：`-Dcarbon.scale` 基准运行两次，掉落物数量为 1000 / 2000 / 4000 时 Carbon 101–182 / 88–178 / 76–93 FPS，关闭 Carbon 76–83 / 44–45 / 22 FPS，约为 1.3–2.2 / 2.0–3.9 / 3.4–4.3 倍。核显频率波动较大，没有掉落物时两次分别为 116 与 214 FPS；关闭 Carbon 时受 CPU 限制，结果稳定。60 秒 JIT 压力测试通过；截图与 OpenGL 下的亮度一致。

**RenderPipeline 路径在 OpenGL 下**（`-Dcarbon.blaze3d=true`，RTX 5070 Laptop）：1000 / 2000 / 4000 个掉落物时，GL 路径 1115 / 954 / 734 FPS，RenderPipeline 路径 976 / 1017 / 826 FPS，关闭 Carbon 85 / 41 / 21 FPS。

**JIT 压力测试**（`-PsmokeStress=60`，每 1.5 秒轮换物理化、总开关与掉落物管线）：拆分 `ItemBatchRenderer.flush` 之前 3 次全部在约 30 秒时因 C2 编译该方法崩溃，拆分后 3 次全部通过；Photon 下 40 秒压力测试同样通过。

## 已知问题与未验证项

- OpenGL 只在 NVIDIA 显卡、Vulkan 只在 Intel 核显上测试，AMD 未验证。
- 未在真实启动器与大型整合包中测试。
- Vulkan 下通用物品的阴影保持原版绘制。
- Iris 阴影帧中的通用物品（手持、展示框等）不实例化。
