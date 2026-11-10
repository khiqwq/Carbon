package first.carbon.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

// 经 Mojang RenderPipeline / RenderPass 的实例化绘制，OpenGL 与 Vulkan 后端共用（Vulkan 下没有 OpenGL 上下文，原有的直接 GL 路径不可用）。
// 实例数据作为步进为 1 的第二个顶点绑定传入，布局与 GL 路径相同；计算着色器预处理不可用，掉落物动画在顶点着色器中计算。
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
    private static final Identifier ITEM_SHADER = Identifier.fromNamespaceAndPath("carbon", "core/b3d_item");
    private static final Identifier SHADOW_SHADER = Identifier.fromNamespaceAndPath("carbon", "core/b3d_shadow");
    private static final Map<String, RenderPipeline> PIPELINES = new HashMap<>();
    private static DynamicUniformStorage<Draw> uniforms;
    private static GpuBuffer corners;

    private B3d() {
    }

    enum Kind { DROPPED, PHYSICS, GENERIC }

    static RenderPipeline item(Kind kind, boolean cull, boolean glint) {
        return PIPELINES.computeIfAbsent(kind + (cull ? "_cull" : "") + (glint ? "_glint" : ""), name -> {
            var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("carbon", "pipeline/" + name.toLowerCase(java.util.Locale.ROOT)))
                    .withVertexShader(ITEM_SHADER)
                    .withFragmentShader(ITEM_SHADER)
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                    .withBindGroupLayout(CARBON)
                    .withVertexBinding(0, DefaultVertexFormat.ENTITY)
                    .withVertexBinding(1, kind == Kind.GENERIC ? GENERIC : DROPPED)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withCull(cull);
            if (kind != Kind.DROPPED) builder.withShaderDefine(kind.name());
            if (glint) {
                builder.withShaderDefine("GLINT")
                        .withColorTargetState(new ColorTargetState(BlendFunction.GLINT))
                        .withDepthStencilState(new DepthStencilState(CompareOp.EQUAL, false));
            } else builder.withDepthStencilState(DepthStencilState.DEFAULT);
            return builder.build();
        });
    }

    private static RenderPipeline shadow() {
        return PIPELINES.computeIfAbsent("shadow", name -> RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
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

    // 每帧在 Mojang 重置动态 uniform 时一并轮换
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
        if (uniforms == null) uniforms = new DynamicUniformStorage<>("Carbon draw UBO", 64, 16);
        return uniforms.writeUniform(new Draw(camera.x(), camera.y(), camera.z(), clock, groundScale, flat ? 1 : 0,
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

    static RenderPass open(String label) {
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        var color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
        var depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, color, Optional.empty(), depth, OptionalDouble.empty());
        var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
        if (scissor.enabled()) pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
        return pass;
    }

    // 一组批次的一次完整绘制（基础通道与可选的附魔光效通道）；实例数据与 uniform 在开启 RenderPass 前全部写好
    static int drawItems(Kind kind, List<GpuItemBatch> batches, Matrix4fc view, Vector3fc camera, float clock, String label) {
        int maxIndices = 0;
        boolean anyFoil = false;
        GpuBufferSlice[] params = new GpuBufferSlice[batches.size()];
        for (int i = 0; i < batches.size(); i++) {
            GpuItemBatch batch = batches.get(i);
            first.carbon.Carbon.SODIUM.mark(batch.sprites);
            batch.upload();
            params[i] = draw(camera, clock, batch.groundScaleY, batch.flatPhysics(), batch.center, batch.halfSize);
            for (var mesh : batch.meshes) maxIndices = Math.max(maxIndices, mesh.indices);
            anyFoil |= batch.foil;
        }
        if (maxIndices == 0) return 0;
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(maxIndices);
        GpuBufferSlice base = transforms(view, false), glint = anyFoil ? transforms(view, true) : null;
        var minecraft = Minecraft.getInstance();
        var lightmap = minecraft.gameRenderer.lightmap();
        var lightSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var glintTexture = minecraft.getTextureManager().getTexture(ItemFeatureRenderer.ENCHANTED_GLINT_ITEM);
        int draws = 0;
        try (RenderPass pass = open(label)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.bindTexture("Sampler2", lightmap, lightSampler);
            for (int glintPass = 0; glintPass < (anyFoil ? 2 : 1); glintPass++) {
                pass.setUniform("DynamicTransforms", glintPass == 0 ? base : glint);
                for (int i = 0; i < batches.size(); i++) {
                    GpuItemBatch batch = batches.get(i);
                    if (glintPass == 1 && !batch.foil) continue;
                    pass.setUniform("CarbonDraw", params[i]);
                    pass.setVertexBuffer(1, batch.instances.slice());
                    for (var mesh : batch.meshes) {
                        pass.setPipeline(item(kind, mesh.cull, glintPass == 1));
                        var texture = glintPass == 0 ? minecraft.getTextureManager().getTexture(mesh.texture) : glintTexture;
                        pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
                        pass.setVertexBuffer(0, mesh.vertices.slice());
                        pass.drawIndexed(mesh.indices, batch.count, 0, 0, 0);
                        draws++;
                    }
                }
            }
        }
        return draws;
    }

    static void drawShadows(GpuBuffer instances, int count, Matrix4fc view, Vector3fc camera, Identifier texture) {
        if (corners == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = stack.malloc(32);
                data.putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(1).putFloat(0).putFloat(1).putFloat(1).flip();
                corners = RenderSystem.getDevice().createBuffer(() -> "Carbon shadow corners", GpuBuffer.USAGE_VERTEX, data);
            }
        }
        GpuBufferSlice params = draw(camera, 0, 0, false, camera, camera);
        GpuBufferSlice transform = transforms(view, false);
        var shadow = Minecraft.getInstance().getTextureManager().getTexture(texture);
        try (RenderPass pass = open("Carbon dropped item shadows")) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(shadow());
            pass.setUniform("DynamicTransforms", transform);
            pass.setUniform("CarbonDraw", params);
            pass.bindTexture("Sampler0", shadow.getTextureView(), shadow.getSampler());
            pass.setVertexBuffer(0, corners.slice());
            pass.setVertexBuffer(1, instances.slice());
            pass.draw(4, count, 0, 0);
        }
    }

    private record Draw(float x, float y, float z, float clock, float groundScale, float flat,
                        float centerX, float centerY, float centerZ, float halfX, float halfY, float halfZ) implements DynamicUniformStorage.DynamicUniform {
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
