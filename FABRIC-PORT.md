# Carbon 26.3 Fabric 移植说明

本分支由 [`26.2`](https://github.com/khiqwq/Carbon/tree/26.2) 分支移植至 Minecraft 26.3 / Fabric。26.2 分支是上游 `26.1.2-neoforge`（0.9-B）的 Fabric 移植，26.1.2 → 26.2 的对照见该分支的 FABRIC-PORT.md。

## 环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | 26.3（不混淆，运行时即 Mojang 名称） |
| Fabric Loader | ≥ 0.19.3（构建使用 0.20.1） |
| Fabric API | 构建使用 0.166.0+26.3 |
| Java | 25 |
| 构建 | Fabric Loom 1.19.4（`net.fabricmc.fabric-loom`，不重映射）· Gradle 9.8.2 |
| 可选 | Mod Menu 21 · Sodium 0.9.2 · Iris 1.11.7（启用光影包时 Carbon 保持原版渲染） |

构建：`gradlew build`，输出 `build/libs/carbon-26.3.9-B-fabric.jar`。

## 26.2 → 26.3 接口变化

| 26.2 | 26.3 |
| --- | --- |
| `com.mojang.blaze3d.{buffers, opengl, pipeline, shaders, textures}`、`GpuFormat`、`PrimitiveTopology`、`CompareOp`、`GpuBackend`、`RenderPass`、`VertexFormat` | 图形接口拆到 `com.mojang.renderpearl.api.*`，后端实现在 `com.mojang.renderpearl.backend.{opengl, vulkan}` |
| GLFW 窗口与 OpenGL 上下文 | SDL3（`lwjgl-sdl`）；`GlDevice` 以 3.3 核心创建 OpenGL 上下文 |
| `DynamicUniforms`、`DynamicUniformStorage` | `DynamicGpuData`、`DynamicGpuDataStorage`（`DynamicGpuDataStorageMapped`） |
| `PreparedRenderType.drawFromBuffer` 每次绘制自行打开 RenderPass | 原版按阶段打开一个 RenderPass，`executeGroup(context, oitStage, renderPass, …)` 在其中执行；通道打开期间不允许写缓冲或上传纹理 |
| `RenderPass.setPipeline(RenderPipeline)`、`bindTexture` | `setPipeline(CompiledRenderPipeline)`（`RenderSystem.getCompiledPipeline` 按需编译并缓存）、`setUniform(name, view, sampler)` |
| `RenderSystem.outputColorTextureOverride`、`PreparedRenderType.outputTarget()` | 移除，渲染目标由打开通道的一方决定 |
| 着色器 `#moj_import`，输入输出不需要位置 | `#include <命名空间:文件.glsl>`；GLSL 经 SPIR-V 以独立着色器对象编译，顶点输入按名称匹配格式，输入输出须显式 `layout(location)` |
| 附魔光效单独的 `glint()` 渲染类型（GLINT 混合、深度相等的第二次绘制） | 材质自带 `itemGlintRenderType()`，`GLINT` 宏与 `GlintSampler` 在同一次绘制中叠加 |
| `ItemFeatureRenderer.Submit.hasTranslucency()`、`submitItem(…, List<BakedQuad>, …)` | `ItemQuads`（全部、实心、半透明），实心与半透明拆成两个提交进入不同阶段；半透明阶段可走顺序无关透明（OIT） |
| `EntityRenderDispatcher.shouldRender(entity, frustum, x, y, z)` | 增加 `partialTicks` 参数 |
| `RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET` | 不再公开，按 `BindGroupLayouts` 组合相同的绑定组 |
| `GlCommandEncoder.drawFromBuffers` | 改为 `GlRenderPass`、`setupDraw`、`executeDraw(s)` |

## 26.3 特有问题（已处理）

1. **共享渲染通道**：Carbon 不再自行打开通道。掉落物与掉落物阴影在 `LevelRenderer.executeSolid` 末尾画进原版的实心通道；通用物品在该物品分组 `executeGroup` 末尾画进当前通道。因为通道打开期间不能写缓冲或上传纹理，实例数据、每次绘制的 uniform、顺序索引缓冲与纹理都在准备阶段完成：掉落物在关卡帧 `prepareFrame` 之后，通用物品在原版上传共享顶点缓冲之前。
2. **两种后端统一走 RenderPipeline**：原有直接 GL 路径依赖自开帧缓冲与计算着色器（OpenGL 4.3），而 26.3 的 OpenGL 上下文为 3.3 核心，且绘制发生在共享通道内。RenderPipeline 路径在 26.2 的 OpenGL 下与 GL 路径性能相当，因此 26.3 的两种后端都使用它。GL 路径代码保留，供日后移植 Iris 桥接；GLFW 版的上下文版本请求（`WindowMixin`）与 `GlCommandEncoderMixin` 已移除。
3. **后台编译管线**：26.3 在后台线程编译管线。原先挂在 `glLinkProgram` 上、为 Iris 桥接捕获程序源码的钩子会在这些线程上调用 GL 并写入非线程安全的表，已移除。
4. **启动前编译**：首次启用时编译全部 13 条管线变体，任一失败则整体保持原版渲染，避免在通道中途才发现管线不可用。

## 实测

测试硬件：OpenGL 下为 NVIDIA GeForce RTX 5070 Laptop GPU（驱动 610.74，上下文 3.3）；Vulkan 下本机选用 Intel Graphics 核显（驱动 101.8424）。Java 25.0.1（与官方启动器为 26.x 提供的运行时相同）。

**冒烟测试**（`gradlew prodClient -PsmokeTest`）：只装 Fabric API 与 Mod Menu、再加装 Sodium 0.9.2 两种组合，OpenGL 与 Vulkan 下均为 400 个掉落物中 250 个实例化为 600 个实例、5 次绘制（附魔光效并入同一次绘制，比 26.2 少一次），250 个圆形阴影 1 次绘制；手持物品走通用路径实例化。默认、物理化、关闭与重新开启四个阶段截图检查无错位，玻璃等半透明物品保持原版。

**Iris 1.11.7 + Photon v1.4**（OpenGL）：Carbon 保持原版渲染，物品显示正常。

**JIT 压力测试**（`-PsmokeStress=60`）：OpenGL 与 Vulkan 均通过。

**规模基准**（`-Dcarbon.scale`，加装 Sodium，每档 8 秒）：

| 掉落物 | 0 | 1000 | 2000 | 4000 |
| --- | --- | --- | --- | --- |
| OpenGL，Carbon | 1576 | 1070 | 1055 | 937 |
| OpenGL，关闭 Carbon | 1585 | 127 | 62 | 26 |
| Vulkan（核显），Carbon | 173 | 137 | 126 | 71 |
| Vulkan（核显），关闭 Carbon | 175 | 99 | 60 | 32 |

单位为平均 FPS。核显的结果在不同运行之间波动较大。

测试机上 26.3 本身约有一半的启动在资源加载阶段以原生访问冲突（`0xC0000005`）退出，不生成 hs_err；去掉 Carbon 的全部 Mixin 与入口后同样出现（4 次中 2 次），与 Carbon 无关，测试时重试。

## 已知问题与未验证项

- 启用 Iris 光影包时不实例化，保持原版物品渲染。
- OpenGL 只在 NVIDIA 显卡、Vulkan 只在 Intel 核显上测试，AMD 未验证。
- 未在真实启动器与大型整合包中测试。
- 通用物品的阴影保持原版绘制。
