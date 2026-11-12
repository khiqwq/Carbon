package first.carbon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import first.carbon.Carbon;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL43.*;

// 在原生物品通道内合批，保留提交者提供的姿态与光照。
// 26.2 先为整帧准备所有特性分组再逐阶段执行：准备阶段截取可实例化的提交，执行到对应物品分组时一并绘制。
public final class GenericItemRenderer {
    // 已准备但尚未关闭的特性帧数；Iris 的阴影帧嵌套在主帧的准备与执行之间，只在最外层帧实例化
    private int depth;
    private int groups, firstGroup = -1;
    private final Map<Key, GpuItemBatch> meshes = new HashMap<>();
    private final Matrix4f identity = new Matrix4f();
    private int frame, program, framebuffer;
    private long meshBytes;
    private boolean active, failed;
    private int irisGeneration;
    public int instances, drawCalls;
    public long totalInstances, totalDraws;

    public void beginFrame() {
        if (depth++ == 0) begin();
    }

    public void endFrame() {
        if (depth > 0) depth--;
    }

    // 每个特性帧执行不透明阶段前补绘掉落物的 Iris 光影阴影；阴影帧嵌套在主帧之内，drawIrisShadows 自行判断是否处于阴影通道
    public void beforeSolid() {
        if (active && Carbon.IRIS.active()) Carbon.RENDERER.drawIrisShadows();
    }

    private void begin() {
        groups = 0;
        firstGroup = -1;
        if (irisGeneration != Carbon.IRIS.generation) {
            invalidate();
            irisGeneration = Carbon.IRIS.generation;
        }
        boolean b3d = Carbon.useB3d();
        active = !failed && Carbon.CONFIG.enabled.get() && Carbon.IRIS.canInstance() && Carbon.IRIS.canSubmit()
                && (b3d || Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture() instanceof com.mojang.renderpearl.backend.opengl.GlTexture
                && GL.getCapabilities().OpenGL33) && RenderSystem.getProjectionMatrixBuffer() != null && RenderSystem.getShaderFog() != null;
        frame = Minecraft.getInstance().gui.hud.getGuiTicks();
        instances = drawCalls = 0;
        if (active && !Carbon.IRIS.active() && !b3d && program == 0) {
            try {
                program = Carbon.RENDERER.createProgram("generic_instanced.vsh", "generic_instanced.fsh");
                glUniformBlockBinding(program, glGetUniformBlockIndex(program, "Projection"), 4);
                glUniformBlockBinding(program, glGetUniformBlockIndex(program, "Fog"), 5);
                framebuffer = glGenFramebuffers();
            } catch (Exception exception) {
                failed = true;
                active = false;
                org.slf4j.LoggerFactory.getLogger("Carbon").error("Keeping native item submissions", exception);
            }
        }
        var iterator = meshes.values().iterator();
        while (iterator.hasNext()) {
            GpuItemBatch batch = iterator.next();
            batch.count = 0;
            if (frame - batch.lastFrame > 300) {
                meshBytes -= batch.meshBytes;
                batch.close();
                iterator.remove();
            }
        }
    }

    // 过滤可实例化的提交，其余交还原版物品特性渲染；分组序号与 RenderTypeFeatureRenderer 的执行序号一致。
    public List<ItemFeatureRenderer.Submit> collect(List<ItemFeatureRenderer.Submit> submits) {
        int group = groups++;
        if (!active || depth != 1) return submits;
        List<ItemFeatureRenderer.Submit> remaining = null;
        for (int i = 0; i < submits.size(); i++) {
            var submit = submits.get(i);
            if (submit(submit)) {
                if (remaining == null) remaining = new java.util.ArrayList<>(submits.subList(0, i));
                if (firstGroup < 0) firstGroup = group;
            } else if (remaining != null) remaining.add(submit);
        }
        return remaining == null ? submits : remaining;
    }

    private boolean submit(ItemFeatureRenderer.Submit submit) {
        if (!active || submit.outlineColor() != 0 || submit.overlayCoords() != OverlayTexture.NO_OVERLAY
                || submit.foilType() == ItemStackRenderState.FoilType.SPECIAL || submit.quads().isEmpty() || translucent(submit.quads())) return false;
        int[] captured = Carbon.IRIS.capture(submit);
        try {
            Key key = new Key(List.copyOf(submit.quads()), new IntArrayList(submit.tintLayers()), submit.foilType(), Carbon.IRIS.identifiers());
            GpuItemBatch batch = meshes.get(key);
            if (batch == null) {
                if (meshes.size() >= 256 || meshBytes >= 67108864) return false;
                ItemStackRenderState state = new ItemStackRenderState();
                var layer = state.newLayer();
                layer.setQuads(ItemQuads.split(submit.quads()));
                for (int tint : submit.tintLayers()) layer.tintLayers().add(tint);
                layer.setFoilType(submit.foilType());
                try {
                    batch = new GpuItemBatch(new ItemStack(Items.STONE), state, Carbon.RENDERER, false, submit);
                    meshes.put(key, batch);
                    meshBytes += batch.meshBytes;
                } catch (RuntimeException exception) {
                    org.slf4j.LoggerFactory.getLogger("Carbon").debug("Keeping native item submission: {}", exception.toString());
                    return false;
                }
            }
            if (batch.count >= 65536) return false;
            batch.add(submit.pose().pose(), submit.pose().normal(), 0, 0, false, submit.lightCoords(), false, 0, 0, 0);
            batch.lastFrame = frame;
            instances++; totalInstances++;
            return true;
        } finally { Carbon.IRIS.restore(captured); }
    }

    // 26.3 把物品的实心与半透明四边形拆成两个提交，半透明部分进入排序与 OIT 阶段，保持原版绘制
    private static boolean translucent(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) if (quad.materialInfo().itemRenderType().hasBlending()) return true;
        return false;
    }

    public void execute(int group, com.mojang.renderpearl.api.commands.RenderPass renderPass) {
        if (depth == 1 && group == firstGroup && renderPass != null) {
            this.renderPass = renderPass;
            try { flush(); } finally { this.renderPass = null; }
        }
    }

    private com.mojang.renderpearl.api.commands.RenderPass renderPass;
    private B3d.Prepared prepared;

    // 原版上传共享顶点缓冲之前（此时没有打开的 RenderPass）上传实例并写好 uniform；模型视图与原版物品类型一样在准备时取得
    public void prepare() {
        prepared = null;
        if (!active || depth != 1 || instances == 0 || Carbon.IRIS.active() || !Carbon.useB3d()) return;
        java.util.List<GpuItemBatch> batches = new java.util.ArrayList<>();
        for (GpuItemBatch batch : meshes.values()) if (batch.count != 0) batches.add(batch);
        if (!batches.isEmpty()) prepared = B3d.prepare(B3d.Kind.GENERIC, batches, RenderSystem.getModelViewStack(), new org.joml.Vector3f(), 0);
    }

    private void flush() {
        if (active && instances != 0) {
            if (Carbon.IRIS.active()) {
                var zero = new org.joml.Vector3f();
                for (GpuItemBatch batch : meshes.values()) if (batch.count != 0) {
                    Carbon.SODIUM.mark(batch.sprites);
                    batch.upload();
                    for (int pass = 0; pass < (batch.foil ? 2 : 1); pass++) for (var mesh : batch.meshes) {
                        var type = pass == 0 ? mesh.type : mesh.glintType;
                        if (pass == 0 || !Carbon.IRIS.shadowPass()) {
                            Carbon.IRIS.draw(type, mesh.vertices, mesh.vertexBuffer, mesh.indexBuffer, mesh.format, mesh.indices,
                                    batch.count, batch.instanceBuffer, 0, zero, 0, 0, batch.center, batch.halfSize);
                            drawCalls++; totalDraws++;
                        }
                    }
                }
            } else if (Carbon.useB3d()) {
                if (prepared != null) {
                    int draws = B3d.draw(renderPass, prepared);
                    prepared = null;
                    drawCalls += draws;
                    totalDraws += draws;
                }
            } else try (GpuFrame gpu = new GpuFrame(framebuffer, true); MemoryStack memory = MemoryStack.stackPush()) {
                var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
                if (scissor.enabled()) { glEnable(GL_SCISSOR_TEST); glScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height()); }
                glUseProgram(program);
                glUniformMatrix4fv(glGetUniformLocation(program, "View"), false, RenderSystem.getModelViewStack().get(memory.mallocFloat(16)));
                glUniform3f(glGetUniformLocation(program, "Camera"), 0, 0, 0);
                glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
                glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
                glUniform4f(glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, 1);
                for (GpuItemBatch batch : meshes.values()) {
                    if (batch.count != 0) {
                        Carbon.SODIUM.mark(batch.sprites);
                        batch.upload();
                        for (int pass = 0; pass < (batch.foil ? 2 : 1); pass++) {
                            glUniform1i(glGetUniformLocation(program, "GlintPass"), pass);
                            if (pass != 0) {
                                glEnable(GL_BLEND);
                                glBlendFuncSeparate(GL_SRC_COLOR, GL_ONE, GL_ZERO, GL_ONE);
                                glDepthFunc(GL_EQUAL);
                                glDepthMask(false);
                            }
                            glUniformMatrix4fv(glGetUniformLocation(program, "TextureMat"), false,
                                    (pass == 0 ? identity : TextureTransform.GLINT_TEXTURING.createMatrix()).get(memory.mallocFloat(16)));
                            glUniform1f(glGetUniformLocation(program, "GlintAlpha"), Minecraft.getInstance().options.glintStrength().get().floatValue());
                            for (var mesh : batch.meshes) {
                                if (mesh.cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
                                gpu.bindTexture(0, pass == 0 ? mesh.texture : ItemFeatureRenderer.ENCHANTED_GLINT_ITEM);
                                mesh.bind();
                                glDrawElementsInstanced(GL_TRIANGLES, mesh.indices, GL_UNSIGNED_INT, 0, batch.count);
                                drawCalls++; totalDraws++;
                            }
                        }
                        glDisable(GL_BLEND);
                        glDepthFunc(GL_GEQUAL);
                        glDepthMask(true);
                    }
                }
            }
        }
    }

    public void invalidate() {
        for (GpuItemBatch batch : meshes.values()) batch.close();
        meshes.clear();
        if (program != 0) glDeleteProgram(program);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        program = framebuffer = 0;
        meshBytes = 0;
        active = failed = false;
        firstGroup = -1;
    }

    private record Key(List<BakedQuad> quads, IntList tints, ItemStackRenderState.FoilType foil, long identifiers) { }
}
