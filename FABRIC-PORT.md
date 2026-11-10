# Carbon 1.21.11 Fabric 移植说明

本分支由 `26.1.2-neoforge`（26.1.2.9-A）回移至 Minecraft 1.21.11 / Fabric。1.21.11 与 26.1 同属提交节点（SubmitNode）与 RenderPipeline 架构，渲染核心、着色器与 Iris 桥接保持一致，仅替换加载器相关部分并按 1.21.11 的接口改写差异处。

## 环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.11 |
| Fabric Loader | ≥ 0.18.0（构建使用 0.19.4） |
| Fabric API | 构建使用 0.141.6+1.21.11 |
| Java | 21 |
| 映射 | Mojang 官方映射 |
| 构建 | Fabric Loom 1.16.1 · Gradle 9.6.1 |
| 可选 | Mod Menu 17（配置界面）· Iris 1.10.x · Sodium 0.8.x |

构建：`gradlew build`，输出 `build/libs/carbon-1.21.11.9-B-fabric.jar`。

## NeoForge 26.1.2 → Fabric 1.21.11 对照

| 26.1.2 NeoForge | 1.21.11 Fabric 实现 |
| --- | --- |
| `@Mod` 构造函数、`ModConfigSpec`、`ConfigurationScreen` | `ClientModInitializer`，自带同格式 `carbon-client.toml` 读写，Mod Menu 配置界面 |
| `AddClientReloadListenersEvent` / `LoggingOut` / `GameShuttingDownEvent` | `ResourceManagerHelper`、`ClientPlayConnectionEvents.DISCONNECT`、`ClientLifecycleEvents.CLIENT_STOPPING`；释放 GL 资源时切回渲染线程 |
| `RenderLevelStageEvent.AfterOpaqueFeatures` | `WorldRenderEvents.AFTER_ENTITIES`（实体特性与不透明批次提交之后） |
| `IClientItemExtensions` | 无等价接口，固定原版散布与浮动 |
| `BakedQuad.materialInfo()`（每面材质） | 1.21.11 每个物品层携带 `RenderType`；按层判断 `Sheets` 中的实心、镂空与半透明物品图集 |
| 26.1 物品图集为镂空 | 1.21.11 普通物品使用带混合的 `translucentItemSheet`；贴图仅含 0/255 透明度时按镂空实例化，其余交还原版排序 |
| `ItemFeatureRenderer.renderSolid / renderTranslucent / renderItem` 直接绘制 | 1.21.11 只有写入 `BufferSource` 的 `render`；用 `@WrapOperation` 过滤 `getItemSubmits()`，方法末尾直接实例化绘制 |
| `ShadowFeatureRenderer.renderTranslucent` | `render`，同样改用 `@WrapOperation` |
| `CameraRenderState` 中的视图矩阵、投影矩阵与雾参数 | 1.21.11 不提供；`renderLevel` 开头记录矩阵，`FogRendererMixin` 记录世界雾参数 |
| `Camera.getViewRotationProjectionMatrix` | 访问加宽 `Frustum.matrix` |
| `LightCoordsUtil` / `Lightmap` / `levelLightmap()` | `LightTexture.pack` / `LightTexture.getBrightness` / `lightTexture().getTextureView()` |
| `CuboidItemModelWrapper` / `DefaultVertexFormat.ENTITY` / `QuadInstance` | `BlockModelWrapper` / `NEW_ENTITY` / `putBulkData` |
| `RenderType.outputTarget()`、`VertexFormatElement.normalized()` | 访问器读取 `RenderSetup.outputTarget`；按 `NORMAL` / `COLOR` 用途判断归一化 |
| NeoForge 早期窗口的 OpenGL 4.6 上下文 | `WindowMixin` 依次请求 4.6 → 4.0，失败回到原版 3.3；计算着色器与 Iris 实例化需要 4.3 |

## Fabric 生产环境特有问题（已修复）

NeoForge 运行时使用 Mojang 名称，Fabric 生产环境使用 intermediary 名称，以下写法在 NeoForge 正常、在 Fabric 静默失效：

1. 网格捕获代理用 `method.getName().equals("submitItem")` 识别调用，生产环境下全部物品被拒绝。改为按参数签名识别。
2. `Item.class.getMethod("isFoil", …)` 找不到方法，所有物品被视为动态光效，削弱场景复用。改用 `MappingResolver` 解析 `method_7886`。
3. `IrisShadowRendererMixin` 的类级 `remap = false` 连带 `@At` 中的 `ClientLevel.entitiesForRendering` 不被重映射，`require = 0` 使光影阴影实例化静默失效。`@At` 单独设 `remap = true`。
4. Fabric 版 Iris 在 `prepareCullFrustum` 之后、主通道提取实体之前渲染光影阴影。帧初始化从 `extractVisibleEntities` 提前到 `renderLevel` 开头，视锥按原版方式构造。

## 实测

测试硬件：NVIDIA GeForce RTX 5070 Laptop GPU，驱动 610.74。

**渲染模组组合**（`gradlew prodClient -PsmokeTest`）：Fabric API 0.141.6、Sodium 0.8.14、Iris 1.10.8、ImmediatelyFast 1.14.3、EntityCulling 1.11.2、MoreCulling 1.6.2、BadOptimizations 2.4.1、Mod Menu 17.0.1。400 个掉落物中 300 个实例化为 650 个实例、7 次绘制，300 个圆形阴影 1 次绘制；界面与手持物品走通用路径实例化。Photon v1.3b 光影下主通道约 30 次 Iris 实例化绘制（749 个实例），光影阴影通道约 32 次（699 个实例）。截图检查无错位，正常退出。

**完整整合包**（`gradlew benchPack`，一个 114 个模组的 1.21.11 整合包的隔离副本，不开光影，渲染距离 16，测量时取消帧率上限，每段 15–40 秒）：

| 场景 | Carbon 关闭 | Carbon 开启 |
| --- | ---: | ---: |
| 无掉落物（关/开交替两轮） | 328.5 / 308.0 FPS | 369.3 / 331.7 FPS |
| 300 个掉落物（交替两轮） | 14.9 / 15.3 FPS | 44.1 / 44.1 FPS |
| 300 个掉落物（另一轮，从开始即生成） | 10.4 / 11.7 FPS | 31.2 / 34.0 FPS |
| 1000 个掉落物 | 3.8 FPS | 12.4 / 12.8 FPS |

修复整合包中另一个客户端模组的逐次栈遍历开销后（见下方问题 7），同一副本重测：

| 场景 | Carbon 关闭 | Carbon 开启 |
| --- | ---: | ---: |
| 无掉落物（交替两轮） | 622.7 / 640.0 FPS | 636.5 / 617.5 FPS |
| 300 个掉落物（交替两轮） | 177.1 / 201.2 FPS | 553.9 / 583.9 FPS |

开启时 300 个掉落物合为约 13 次实例化绘制，阴影 1 次绘制。不同轮次之间视角与地形不同，同一轮内的开关对比有效。无掉落物时开启 Carbon 不降低帧率（早期一次测量的差异来自区块加载期间的先后顺序，改为交替测量后消失）。2026-11-10 在同一副本重测 1000 个掉落物：关闭 56.6 / 58.5 FPS，开启 189.2 / 195.1 FPS，1% low 由约 26 提升到约 57。

**与上游相同测法的对照**（`gradlew prodClient -PsmokeTest -PprodJvmArgs="-Dcarbon.scale=0,500,1000,2000,4000"`，`ScaleBench`）：参照上游 CurseForge 页面的测试方式，超平坦世界中不可堆叠、不会合并的下界合金剑按 0.5 格间距落地静止，关闭垂直同步、不限帧率、隐藏界面并关闭 AFK 限帧，每个数量交替测量开启与关闭各 8–10 秒。窗口为 854×480，未装 Lithium 时同时装有 Fabric API、Sodium 与 Iris（不开光影）。

| 掉落物 | 正常刻 + Lithium | 冻结刻（`/tick freeze`） | 正常刻，无 Lithium |
| ---: | ---: | ---: | ---: |
| 0 | 1949 / 1808 FPS | 1449 / 1647 FPS | 1876 / 1338 FPS |
| 500 | 1260 / 227 FPS | 1720 / 277 FPS | 1330 / 235 FPS |
| 1000 | 1421 / 120 FPS | 1301 / 117 FPS | 992 / 118 FPS |
| 2000 | 1305 / 61 FPS | 1320 / 53 FPS | 1136 / 59 FPS |
| 4000 | 1197 / 37 FPS | 1407 / 35 FPS | 849 / 23 FPS |

每格为“开启 / 关闭”，正常刻 + Lithium 一列中 1000–4000 为两轮平均。冻结刻时开启 Carbon 的帧率不随掉落物数量下降，与上游图表的平线一致；同轮测量的波动约 ±15%。正常刻时剩余的随数量下降来自原版客户端的 `ItemEntity.tick`：落地物品每 4 刻执行一次 `move`，在密集堆放时每次碰撞查询都要遍历同一区段内上千个实体（JFR 中占渲染线程 Java 样本约 36%，Carbon 自身未出现在样本中），另有约 10% 来自准星实体拾取。装上 Lithium 后这部分消失，帧率在 500–4000 个之间基本持平，与上游在 10000 把剑测试中搭配 Lithium + Sodium 的做法一致。场景复用正常：每个游戏刻约重建一次，其余帧直接复用（约 93 000 次复用对 863 次重建）。

结论：在相同条件下，移植版的实例化与场景复用表现与上游设计一致；早先“300 个掉落物 15 → 44 FPS”的数字来自上述整合包问题修复之前，不代表 Carbon 本身的上限。

## 可能的问题

1. **OpenGL 上下文。** `WindowMixin` 提升上下文版本；若其他模组以 `@Redirect` 替换 `glfwCreateWindow` 会冲突，可用 `-Dcarbon.vanillaContext=true` 关闭。已与 Ixeris（GLFW 线程化）联装验证。macOS 最高 4.1，计算着色器与 Iris 实例化不可用。
2. **物品扩展与 Fabric Renderer API。** 无 `IClientItemExtensions`；仅 `BlockModelWrapper` 且使用原版 `Sheets` 渲染类型的物品参与缓存，FRAPI 模型、自定义渲染类型、带半透明像素的物品（如染色玻璃）和指南针等动态模型保留原生渲染。
3. **Fabulous 画质。** 实例化物品直接绘制到主渲染目标，不经过 Fabulous 的 `item_entity` 目标；由于只实例化不透明与镂空像素，正常情况下无可见差异。
4. **绘制时机。** 1.21.11 的物品特性写入 `BufferSource` 后统一提交，Carbon 在 `ItemFeatureRenderer.render` 末尾立即绘制实例，早于同批其他实体几何；依赖深度测试保证遮挡正确，半透明物品已排除。
5. **Iris。** 已验证 Iris 1.10.8 与 Photon v1.3b 的主通道和光影阴影；其他光影包未测试。Iris 在开发环境中因 refmap 无法运行，需使用 `prodClient`。
6. **运行中切换 `enabled`。** 立即生效；配置写回需执行 `/carbon enabled` 或在配置界面完成。
7. **整合包本身的开销。** 上表第一组中，关闭 Carbon 时 300 个掉落物仅约 10–15 FPS。按组移除模组定位到另一个客户端模组：它在每次读取配置开关时用 `StackWalker` 遍历完整调用栈，而该开关在渲染与 tick 路径上被频繁读取；这类开销发生在 JVM 内部，JFR 无法采样。改为仅对需要的开关遍历后，关闭 Carbon 时为约 190 FPS，开启 Carbon 时约 570 FPS。该问题与 Carbon 无关。
8. **NeoForge 专用兼容。** 26.1.2 分支针对 Chloride 的实体筛选兼容在 Fabric 中无对应模组，相关代码保留但不会触发。
