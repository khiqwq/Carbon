package first.carbon.client;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 经 RenderPipeline 的实例化绘制，OpenGL 与 Vulkan 后端共用。26.3 的特性在原版打开的 RenderPass 中执行，Carbon 画进同一个通道。
// 实例数据作为步进为 1 的第二个顶点绑定传入；没有计算着色器，掉落物动画在顶点着色器中计算。
public final class B3d {
    // 掉落物实例：位置、光照（两个 16 位无符号数）、年龄与相位、副本偏移与浮动 / 速率，共 40 字节
    static final VertexFormat DROPPED = VertexFormat.builder(1)
            .addAttribute("Translation", GpuFormat.RGB32_FLOAT)
            .addAttribute("Light", GpuFormat.RG16_UINT)
            .addAttribute("Animation", GpuFormat.RG32_FLOAT)
            .addAttribute("Copy", GpuFormat.RGBA32_FLOAT)
            .build();
    // 通用物品实例：变换矩阵、动画参数、光照、法线矩阵、副本偏移，共 144 字节
    static final VertexFormat GENERIC = VertexFormat.builder(1)
            .addAttribute("Instance", 0, 16, GpuFormat.RGBA32_FLOAT, 4)
            .addAttribute("Animation", GpuFormat.RGBA32_FLOAT)
            .addAttribute("Data", GpuFormat.RG32_FLOAT)
            .addAttribute("InstanceNormal", 88, 12, GpuFormat.RGB32_FLOAT, 3)
            .addAttribute("CopyOffset", 20, GpuFormat.RGB32_FLOAT)
            .build();
    static final VertexFormat SHADOW = VertexFormat.builder(1)
            .addAttribute("Bounds", GpuFormat.RGBA32_FLOAT)
            .addAttribute("Shadow", GpuFormat.RGBA32_FLOAT)
            .addAttribute("Parameters", GpuFormat.RGBA32_FLOAT)
            .build();
    private static final VertexFormat CORNER = VertexFormat.builder(0).addAttribute("Corner", GpuFormat.RG32_FLOAT).build();
    private static final BindGroupLayout CARBON = BindGroupLayout.builder().withUniform("CarbonDraw", UniformType.UNIFORM_BUFFER).build();
    // 原版的 MATRICES_FOG_LIGHT_DIR_SNIPPET 在 26.3 中不再公开，按相同的绑定组组合
    private static final RenderPipeline.Snippet MATRICES_FOG = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .buildSnippet();
    private static final Identifier ITEM_SHADER = Identifier.fromNamespaceAndPath("carbon", "core/b3d_item");
    private static final Identifier SHADOW_SHADER = Identifier.fromNamespaceAndPath("carbon", "core/b3d_shadow");
    private static final Map<String, RenderPipeline> PIPELINES = new HashMap<>();
    private static DynamicGpuDataStorage<Draw> uniforms;
    private static GpuBuffer corners;

    private B3d() {
    }

    enum Kind { DROPPED, PHYSICS, GENERIC }

    // 附魔光效与原版 26.3 一致，在同一次绘制中经 GlintSampler 叠加，不再另开混合通道
    static RenderPipeline item(Kind kind, boolean cull, boolean glint) {
        return PIPELINES.computeIfAbsent(kind + (cull ? "_cull" : "") + (glint ? "_glint" : ""), name -> {
            var builder = RenderPipeline.builder(MATRICES_FOG)
                    .withLocation(Identifier.fromNamespaceAndPath("carbon", "pipeline/" + name.toLowerCase(java.util.Locale.ROOT)))
                    .withVertexShader(ITEM_SHADER)
                    .withFragmentShader(ITEM_SHADER)
                    .withBindGroupLayout(BindGroupLayouts.LIGHTING)
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                    .withBindGroupLayout(CARBON)
                    .withVertexBinding(0, DefaultVertexFormat.ENTITY)
                    .withVertexBinding(1, kind == Kind.GENERIC ? GENERIC : DROPPED)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withCull(cull)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withDepthStencilState(DepthStencilState.DEFAULT);
            if (kind != Kind.DROPPED) builder.withShaderDefine(kind.name());
            if (glint) builder.withShaderDefine("GLINT").withBindGroupLayout(BindGroupLayouts.GLINT_SAMPLER);
            return builder.build();
        });
    }

    private static RenderPipeline shadow() {
        return PIPELINES.computeIfAbsent("shadow", name -> RenderPipeline.builder(MATRICES_FOG)
                .withLocation(Identifier.fromNamespaceAndPath("carbon", "pipeline/shadow"))
                .withVertexShader(SHADOW_SHADER)
                .withFragmentShader(SHADOW_SHADER)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withBindGroupLayout(CARBON)
                .withVertexBinding(0, CORNER)
                .withVertexBinding(1, SHADOW)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                .withCull(false)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .build());
    }

    // 首次启用时编译全部变体；任一失败则整体保持原版渲染，避免在通道中途才发现管线不可用
    static boolean compile() {
        for (Kind kind : Kind.values()) for (int variant = 0; variant < 4; variant++) {
            if (RenderSystem.getCompiledPipelineNullable(item(kind, (variant & 1) != 0, (variant & 2) != 0)) == null) return false;
        }
        return RenderSystem.getCompiledPipelineNullable(shadow()) != null;
    }

    private static CompiledRenderPipeline compiled(RenderPipeline pipeline) {
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
        if (compiled == null) throw new IllegalStateException("Carbon pipeline " + pipeline.getLocation() + " is not compiled");
        return compiled;
    }

    // 每帧在原版重置动态数据时一并轮换
    public static void endFrame() {
        if (uniforms != null) uniforms.endFrame();
    }

    public static void close() {
        if (uniforms != null) uniforms.close();
        if (corners != null) corners.close();
        uniforms = null;
        corners = null;
    }

    static GpuBufferSlice draw(Vector3fc camera, float clock, float groundScale, boolean flat, Vector3fc center, Vector3fc halfSize) {
        if (uniforms == null) uniforms = new DynamicGpuDataStorageMapped<>("Carbon draw UBO", 64, GpuBuffer.USAGE_UNIFORM, 16);
        return uniforms.writeData(new Draw(camera.x(), camera.y(), camera.z(), clock, groundScale, flat ? 1 : 0,
                center.x(), center.y(), center.z(), halfSize.x(), halfSize.y(), halfSize.z()));
    }

    static GpuBufferSlice transforms(Matrix4fc view, boolean glint) {
        return RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(view), glint ? TextureTransform.GLINT_TEXTURING.createMatrix() : new Matrix4f());
    }

    // 上传 [first, last) 的变化区间，容量不足时重建缓冲
    static GpuBuffer upload(GpuBuffer buffer, String label, ByteBuffer data, long capacityBytes, int first, int last) {
        if (buffer == null || buffer.size() < capacityBytes) {
            if (buffer != null) buffer.close();
            buffer = RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacityBytes);
            first = 0;
        }
        if (first < last) RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(first, last - first), data.slice(first, last - first));
        return buffer;
    }

    // 共享通道中其他绘制可能留下裁剪，按物品绘制的裁剪状态重新设置
    private static void scissor(RenderPass pass) {
        var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
        if (scissor.enabled()) pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
        else pass.disableScissor();
    }

    // 26.3 在 RenderPass 打开期间不允许写缓冲：实例数据、uniform 与索引缓冲在准备阶段（原版上传共享顶点缓冲之前）全部写好，
    // 执行阶段只在原版的 RenderPass 中绑定与绘制。
    // 纹理在准备阶段取得：首次使用的纹理会在 getTexture 中上传，执行阶段不能再触发
    record Prepared(Kind kind, List<GpuItemBatch> batches, GpuBufferSlice[] params, GpuBufferSlice base, GpuBufferSlice glint,
                    GpuBuffer indexBuffer, IndexType indexType, Map<Identifier, AbstractTexture> textures, AbstractTexture glintTexture) {}

    record PreparedShadows(GpuBuffer instances, int count, GpuBufferSlice params, GpuBufferSlice transform, AbstractTexture texture) {}

    static Prepared prepare(Kind kind, List<GpuItemBatch> batches, Matrix4fc view, Vector3fc camera, float clock) {
        int maxIndices = 0;
        boolean anyFoil = false;
        GpuBufferSlice[] params = new GpuBufferSlice[batches.size()];
        var textureManager = Minecraft.getInstance().getTextureManager();
        Map<Identifier, AbstractTexture> textures = new HashMap<>();
        for (int i = 0; i < batches.size(); i++) {
            GpuItemBatch batch = batches.get(i);
            first.carbon.Carbon.SODIUM.mark(batch.sprites);
            batch.upload();
            params[i] = draw(camera, clock, batch.groundScaleY, batch.flatPhysics(), batch.center, batch.halfSize);
            for (var mesh : batch.meshes) {
                maxIndices = Math.max(maxIndices, mesh.indices);
                textures.computeIfAbsent(mesh.texture, textureManager::getTexture);
            }
            anyFoil |= batch.foil;
        }
        if (maxIndices == 0) return null;
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(maxIndices);
        return new Prepared(kind, List.copyOf(batches), params, transforms(view, false), anyFoil ? transforms(view, true) : null, indexBuffer, indices.type(),
                textures, anyFoil ? textureManager.getTexture(ItemFeatureRenderer.ENCHANTED_GLINT_ITEM) : null);
    }

    static int draw(RenderPass pass, Prepared prepared) {
        var lightmap = Minecraft.getInstance().gameRenderer.lightmap();
        var lightSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var glintTexture = prepared.glintTexture();
        int draws = 0;
        pass.pushDebugGroup(() -> "Carbon " + prepared.kind().name().toLowerCase(java.util.Locale.ROOT) + " items");
        try {
            scissor(pass);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setIndexBuffer(prepared.indexBuffer(), prepared.indexType());
            for (int i = 0; i < prepared.batches().size(); i++) {
                GpuItemBatch batch = prepared.batches().get(i);
                if (batch.instances == null || batch.closed) continue;
                pass.setVertexBuffer(1, batch.instances.slice());
                for (var mesh : batch.meshes) {
                    pass.setPipeline(compiled(item(prepared.kind(), mesh.cull, batch.foil)));
                    pass.setUniform("DynamicTransforms", batch.foil ? prepared.glint() : prepared.base());
                    pass.setUniform("CarbonDraw", prepared.params()[i]);
                    pass.setUniform("Sampler2", lightmap, lightSampler);
                    var texture = prepared.textures().get(mesh.texture);
                    pass.setUniform("Sampler0", texture.getTextureView(), texture.getSampler());
                    if (batch.foil) pass.setUniform("GlintSampler", glintTexture.getTextureView(), glintTexture.getSampler());
                    pass.setVertexBuffer(0, mesh.vertices.slice());
                    pass.drawIndexed(mesh.indices, batch.count, 0, 0, 0);
                    draws++;
                }
            }
        } finally {
            pass.popDebugGroup();
        }
        return draws;
    }

    static PreparedShadows prepareShadows(GpuBuffer instances, int count, Matrix4fc view, Vector3fc camera, Identifier texture) {
        if (corners == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = stack.malloc(32);
                data.putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(1).putFloat(0).putFloat(1).putFloat(1).flip();
                corners = RenderSystem.getDevice().createBuffer(() -> "Carbon shadow corners", GpuBuffer.USAGE_VERTEX, data);
            }
        }
        return new PreparedShadows(instances, count, draw(camera, 0, 0, false, camera, camera), transforms(view, false),
                Minecraft.getInstance().getTextureManager().getTexture(texture));
    }

    static void drawShadows(RenderPass pass, PreparedShadows prepared) {
        var shadow = prepared.texture();
        pass.pushDebugGroup(() -> "Carbon dropped item shadows");
        try {
            scissor(pass);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(compiled(shadow()));
            pass.setUniform("DynamicTransforms", prepared.transform());
            pass.setUniform("CarbonDraw", prepared.params());
            pass.setUniform("Sampler0", shadow.getTextureView(), shadow.getSampler());
            pass.setVertexBuffer(0, corners.slice());
            pass.setVertexBuffer(1, prepared.instances().slice());
            pass.draw(4, prepared.count(), 0, 0);
        } finally {
            pass.popDebugGroup();
        }
    }

    private record Draw(float x, float y, float z, float clock, float groundScale, float flat,
                        float centerX, float centerY, float centerZ, float halfX, float halfY, float halfZ) implements DynamicGpuDataStorage.DynamicGpuData {
        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer)
                    .putVec4(x, y, z, clock)
                    .putVec4(groundScale, 0, flat, 0)
                    .putVec4(centerX, centerY, centerZ, 0)
                    .putVec4(halfX, halfY, halfZ, 0);
        }
    }
}
