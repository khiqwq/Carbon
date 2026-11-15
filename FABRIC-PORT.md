# Carbon 1.21.4 Fabric 移植说明

本分支由 `1.21.1` 分支（1.21.1.9-B-fabric）移植，渲染核心、着色器与 Iris 桥接保持一致，适配 1.21.4 的物品模型解析、实体渲染状态与着色器接口。

## 环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.4 |
| Fabric Loader | ≥ 0.16.0（构建使用 0.16.14） |
| Fabric API | 构建使用 0.119.4+1.21.4，运行时任意 1.21.4 版本 |
| Java | 21 |
| 映射 | Mojang 官方映射 |
| 构建 | Fabric Loom 1.16.1 · Gradle 9.6.1 |
| 可选 | Mod Menu 13.0.4（配置界面）· Iris 1.8.8（光影）· Sodium 0.6.13 |

构建：`gradlew build`，输出 `build/libs/carbon-1.21.4.9-B-fabric.jar`。

## 1.21.1 → 1.21.4 对照

| 1.21.1 | 1.21.4 实现 |
| --- | --- |
| `ItemRenderer.renderModelLists(BakedModel, ItemStack, …)` | 第二个参数改为 `int[] tintLayers`，不再传入物品；新增 `ItemStackRenderStateMixin` 与 `ItemModelResolverMixin`，在解析渲染状态时记录对应物品，渲染期间作为当前物品供 `renderModelLists` 取用 |
| `ItemRenderer.getItemModelShaper()` / `getModel(stack, level, entity, seed)` | `ItemModelResolver.updateForTopItem` 解析出 `ItemStackRenderState`，模型取自 `layers[0].model`（访问加宽） |
| `ItemOverrides.getOverrides()` 判定动态模型 | 1.21.4 无物品覆盖表，改以解析出的模型类是否为 `SimpleBakedModel` 判定 |
| `ItemRenderer.render(stack, context, …)` | `ItemRenderer.renderStatic(stack, context, light, overlay, pose, buffers, level, seed)` |
| `ItemStack.hasFoil()` 重定向 | 光效由 `ItemStackRenderState.FoilType` 决定；改为在 `renderItem` 头部把 `FoilType` 置为 `NONE`，避免采集模型几何时重复写入光效通道 |
| `RenderType.entityTranslucentCull` / `entityGlintDirect` | `itemEntityTranslucentCull` / `armorEntityGlint` |
| `ItemBlockRenderTypes.getRenderType(stack, true)` | `getRenderType(stack)` |
| `ShaderInstance` | `CompiledShaderProgram`（`getId()` → `getProgramId()`，`setSampler` → `bindSampler`） |
| `RenderSystem.getShaderFogColor/Start/End/Shape` | `RenderSystem.getShaderFog()` 返回 `FogParameters` |
| `NativeImage.getPixelRGBA` | `NativeImage.getPixel`（同为 ARGB） |
| `ItemTransforms.ground` 字段 | 记录访问器 `ground()` |
| `ItemEntityRenderer.getSeedForItemStack` | `ItemClusterRenderState.getSeedForItemStack` |
| `EntityRenderer<T>` / `render(entity, yaw, partialTick, …)` | 实体渲染状态：`EntityRenderer<T, S>`、`render(state, pose, buffers, light)`；实体在 `extractRenderState` 头部记录，供渲染与阴影阶段取回 |
| `EntityRenderDispatcher.renderShadow(…, Entity, …)` | 参数改为 `EntityRenderState`，同样经状态取回物品实体 |
| `LevelRenderer.renderLevel(DeltaTracker, …)` | 头部新增 `GraphicsResourceAllocator`、去掉 `LightTexture`；`entitiesForRendering` 的调用移至 `collectVisibleEntities` |
| `Minecraft.tell` | `Minecraft.execute` |

原有的实体分发、缓冲区批处理、光源标记、窗口上下文等 Mixin 目标签名不变，直接沿用。

## 实测

测试硬件：NVIDIA GeForce GTX 750，驱动 560.94，OpenGL 4.6。生产环境 Jar（`gradlew prodClient -PsmokeTest`），模组组合 Carbon + Fabric API 0.119.4 + Sodium 0.6.13 + Iris 1.8.8（未加载光影包）+ Mod Menu 13.0.4。冒烟测试模组（`src/smoketest`，不进入发布 Jar）创建平坦世界，生成 400 个掉落物并在物品栏放入 9 个物品。

| 场景 | 结果 |
| --- | --- |
| 含指南针、附魔书、染色玻璃的混合场景 | 349 个实体实例化为 695 个实例、31–33 次绘制；圆形阴影 349 个合为 1 次绘制；染色玻璃按设计回退原版 |
| 仅静态物品 | 399 个实体实例化为 895 个实例、8 次绘制，阴影 399 个合为 1 次绘制；743 次场景复用 / 58 次重建（物理模式 708 / 1） |
| 物理旋转开启 | 正常运行，平面物品平放，无错位 |
| 通用模型路径（界面与手持） | 9–10 次模型提交实例化为 10 次绘制 |
| 运行中关闭 / 重新开启 | 统计归零后恢复，无错误 |
| 渲染一致性 | 同一场景间隔 2 tick 的实例化与原生渲染截图对比：天空区像素差 0%，整体平均色差 5.1/255，差异集中在物品浮动与光效相位 |
| 帧率（上限 120） | 默认 117–118，物理 119，关闭 117，重新开启 118–119 |

## 可能的问题

1. **OpenGL 上下文版本。** 原版只请求 OpenGL 3.2 核心上下文，NVIDIA 驱动会精确返回 3.2，Carbon 因缺少 3.3 而整体停用。Fabric 没有 NeoForge 的早期窗口机制，因此由 `WindowMixin` 依次请求 4.6 → 3.3 核心上下文，失败回到原版 3.2。其他同样 `@Redirect` 替换 `glfwCreateWindow` 的模组会与之冲突；如遇窗口创建问题，可用 JVM 参数 `-Dcarbon.vanillaContext=true` 恢复原版行为（此时 Carbon 在只提供 3.2 的驱动上不生效）。macOS 最高为 4.1，计算着色器路径不可用，回退顶点着色器路径。
2. **光影包。** Iris 桥接全部通过反射完成，1.21.4 下着色的程序类型由 `ShaderInstance` 换为 `CompiledShaderProgram`。Iris 1.8.8 已实测运行且未阻断实例化，但尚未加载光影包验证主渲染与阴影通道；其他光影包未测试。Iris 在 Mojang 映射开发环境中会因自身 refmap 失败而崩溃，带 Iris 的测试需使用 `prodClient`。
3. **物品客户端扩展。** Fabric 没有 `IClientItemExtensions`，所有物品均按原版散布与浮动处理。通过 Mixin 修改 `ItemEntityRenderer` 的 Fabric 模组可能被专用管线绕过，可关闭 `optimizeDroppedItems` 恢复。
4. **多渲染通道模型。** 1.21.4 的物品模型由 `ItemModel` 逐层输出，Carbon 只接管解析结果为 `SimpleBakedModel` 的单层模型；Fabric Renderer API（FRAPI）模型、Continuity 等包装模型自动回退原生渲染，不会出错但不会被优化。
5. **Sodium 物品快速路径。** Sodium 同样在 `ItemRenderer.renderModelLists` 头部注入并取消。实测 Carbon 先执行；若 Mixin 应用顺序因其他模组改变，通用路径会静默回退到 Sodium 处理，掉落物专用管线不受影响。
6. **其他渲染模组。** ImmediatelyFast、EntityCulling、Entity Model Features 等 Fabric 常见模组未测试。ImmediatelyFast 会改动 `BufferSource` 批处理，可能影响 `BufferSourceMixin` 的批次刷新时机。
7. **配置文件。** 使用自带的简化 TOML 读写，只识别 `键 = 值` 行；无法解析的条目回退默认值并在日志中警告，文件在启动时按标准格式重写。
8. **生产环境反射名称。** Fabric 生产环境使用 intermediary 名称，`Item.isFoil` 经 `MappingResolver` 解析 `method_7886`；解析失败时所有物品被视为动态光效，场景只能在同一 tick 内复用。
9. **缓存失败的模型。** 构建网格失败（例如含半透明像素需原版排序）的模型记入拒绝列表，不再逐帧重试并重复输出警告。
