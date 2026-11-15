package first.carbon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import first.carbon.Carbon;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.opengl.GL33.*;

public final class ItemBatchRenderer {
    // 渲染状态只携带几何，物品由解析阶段记录并在渲染期间作为当前上下文。
    private static final List<ItemStack> context = new ArrayList<>();
    private final Logger logger = LoggerFactory.getLogger("Carbon");
    private final Int2ObjectOpenHashMap<List<GpuItemBatch>> buckets = new Int2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<ItemStack, StackCache> stacks = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<MultiBufferSource.BufferSource, Source> sources = new Reference2ObjectOpenHashMap<>();
    private final IdentityHashMap<RenderType, Boolean> supportedTypes = new IdentityHashMap<>();
    private final Reference2ObjectOpenHashMap<CompiledShaderProgram, int[]> samplerUnits = new Reference2ObjectOpenHashMap<>();
    final IdentityHashMap<SpriteContents, Boolean> opaqueSprites = new IdentityHashMap<>();
    private final int[] uniforms = new int[16];
    private int program;
    private int frame;
    private boolean failed;
    private int irisGeneration = -1;
    private final org.joml.Vector3f noTransform = new org.joml.Vector3f();
    private MethodHandle spriteMarker;
    private boolean spriteApiChecked;
    private GpuShadowBatch shadows;
    private final Matrix4f shadowView = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();
    private Vec3 shadowCamera = Vec3.ZERO;
    private double originX, originY, originZ;
    private boolean world;
    boolean capturing;
    public int instances;
    public int drawCalls;
    public int cacheMisses;
    public long uploadedBytes;
    public long modelBytes;
    public int shadowInstances;
    public int shadowDrawCalls;

    public static void pushStack(ItemStack stack) {
        context.add(stack);
    }

    public static void popStack() {
        if (!context.isEmpty()) context.remove(context.size() - 1);
    }

    public void beginFrame() {        if (irisGeneration != Carbon.IRIS.generation) {
            clear();
            irisGeneration = Carbon.IRIS.generation;
        }
        frame++;
        instances = 0;
        drawCalls = 0;
        cacheMisses = 0;
        uploadedBytes = 0;
        modelBytes = 0;
        shadowInstances = 0;
        shadowDrawCalls = 0;
        world = false;
        if (stacks.size() > 16384) stacks.values().removeIf(cache -> frame - cache.lastFrame > 1);
        if ((frame & 63) == 0) {
            long allocated = 0;
            int models = 0;
            for (var bucket : buckets.values()) {
                for (GpuItemBatch batch : bucket) {
                    allocated += batch.meshBytes;
                    models++;
                }
            }
            for (Source source : sources.values()) {
                for (var draws : source.layers.values()) {
                    for (Submission draw : draws.values()) allocated += draw.capacity * 256L;
                }
            }
            var bucketIterator = buckets.values().iterator();
            while (bucketIterator.hasNext()) {
                var bucket = bucketIterator.next();
                var iterator = bucket.iterator();
                while (iterator.hasNext()) {
                    GpuItemBatch batch = iterator.next();
                    if (frame - batch.lastFrame > 300 || (frame - batch.lastFrame > 1 && (allocated > 67108864 || models > 256))) {
                        allocated -= batch.meshBytes;
                        models--;
                        batch.close();
                        iterator.remove();
                    }
                }
                if (bucket.isEmpty()) bucketIterator.remove();
            }
            var sourceIterator = sources.values().iterator();
            while (sourceIterator.hasNext()) {
                Source source = sourceIterator.next();
                var layers = source.layers.values().iterator();
                while (layers.hasNext()) {
                    var draws = layers.next();
                    var iterator = draws.values().iterator();
                    while (iterator.hasNext()) {
                        Submission draw = iterator.next();
                        if (draw.batch.closed || frame - draw.lastFrame > 300) {
                            draw.close();
                            iterator.remove();
                        }
                    }
                    if (draws.isEmpty()) layers.remove();
                }
                if (source.layers.isEmpty()) sourceIterator.remove();
            }
            stacks.values().removeIf(cache -> frame - cache.lastFrame > 300 || (cache.batch != null && cache.batch.closed));
        }
    }

    public void beginWorld(Camera camera, Matrix4f view, Matrix4f projection) {
        if (!failed && Carbon.CONFIG.enabled.get() && !Carbon.IRIS.active() && GL.getCapabilities().OpenGL33) {
            if (shadows == null) {
                int vertex = 0, fragment = 0, linked = 0;
                try {
                    vertex = compile(GL_VERTEX_SHADER, "shadow.vsh");
                    fragment = compile(GL_FRAGMENT_SHADER, "shadow.fsh");
                    linked = glCreateProgram();
                    glAttachShader(linked, vertex);
                    glAttachShader(linked, fragment);
                    glLinkProgram(linked);
                    if (glGetProgrami(linked, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(linked));
                    shadows = new GpuShadowBatch(linked);
                } catch (Exception exception) {
                    if (linked != 0) glDeleteProgram(linked);
                    failed = true;
                    logger.error("Shadow instancing unavailable; retaining original rendering", exception);
                } finally {
                    if (vertex != 0) glDeleteShader(vertex);
                    if (fragment != 0) glDeleteShader(fragment);
                }
            }
            if (shadows != null) {
                shadowCamera = camera.getPosition();
                originX = Math.floor(shadowCamera.x / 256) * 256;
                originY = Math.floor(shadowCamera.y / 256) * 256;
                originZ = Math.floor(shadowCamera.z / 256) * 256;
                shadowView.set(view);
                shadowProjection.set(projection);
                shadows.count = 0;
                shadows.flushed = false;
                shadows.receivers.clear();
                world = true;
            }
        }
    }

    public boolean submitShadow(ItemEntity entity, float partialTick, float strength, MultiBufferSource buffers) {
        boolean submitted = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (world && shadows != null && !shadows.flushed && Carbon.CONFIG.enabled.get()
                && entity != null && buffers == minecraft.renderBuffers().bufferSource() && entity.getClass() == ItemEntity.class
                && minecraft.level.getEntity(entity.getId()) == entity
                && minecraft.getEntityRenderDispatcher().getRenderer(entity).getClass() == ItemEntityRenderer.class) {
            shadows.add(entity, partialTick, strength, originX, originY, originZ);
            shadowInstances = shadows.count;
            submitted = true;
        }
        return submitted;
    }

    public void flushShadows() {
        if (world && shadows != null && shadows.count > 0 && !shadows.flushed) {
            int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int buffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
            try {
                uploadedBytes += shadows.draw(shadowView, shadowProjection, shadowCamera, originX, originY, originZ);
                shadowDrawCalls++;
            } finally {
                glUseProgram(previousProgram);
                glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, buffer);
            }
        }
    }

    public void endWorld() {
        flushShadows();
        world = false;
    }

    public boolean submit(BakedModel model, int[] tintLayers, int light, int overlay, PoseStack pose, VertexConsumer vertices) {
        boolean submitted = false;
        ItemStack stack = context.isEmpty() ? null : context.get(context.size() - 1);
        if (stack != null && !capturing && !failed && Carbon.CONFIG.enabled.get() && Carbon.IRIS.canInstance() && !stack.isEmpty()
                && model.getClass() == SimpleBakedModel.class && GL.getCapabilities().OpenGL33) {
            Target first = vertices instanceof Target target ? target : null;
            Target second = null;
            if (vertices instanceof VertexPair pair) {
                first = pair.imo$first() instanceof Target target ? target : null;
                second = pair.imo$second() instanceof Target target ? target : null;
                if (second == null) first = null;
            }
            if (first != null && first.imo$source() != null && supported(first.imo$type())
                    && (second == null || (second.imo$source() == first.imo$source() && supported(second.imo$type())))) {
                if (program == 0) {
                    int vertex = 0, fragment = 0;
                    try {
                        vertex = compile(GL_VERTEX_SHADER, "instanced.vsh");
                        fragment = compile(GL_FRAGMENT_SHADER, "instanced.fsh");
                        program = glCreateProgram();
                        glAttachShader(program, vertex);
                        glAttachShader(program, fragment);
                        glLinkProgram(program);
                        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
                        String[] names = {"ModelViewMat", "ProjMat", "TextureMat", "FogShape", "Light0", "Light1", "Sampler0", "Sampler1", "Sampler2", "ColorModulator", "FogStart", "FogEnd", "FogColor", "GlintPass", "GlintAlpha", "UseOverlay"};
                        for (int i = 0; i < names.length; i++) uniforms[i] = glGetUniformLocation(program, names[i]);
                        logger.info("GPU model instancing ready: {}", glGetString(GL_RENDERER));
                    } catch (Exception exception) {
                        if (program != 0) glDeleteProgram(program);
                        program = 0;
                        failed = true;
                        logger.error("Model instancing unavailable; retaining original rendering", exception);
                    } finally {
                        if (vertex != 0) glDeleteShader(vertex);
                        if (fragment != 0) glDeleteShader(fragment);
                    }
                }
                if (program != 0) {
                    int version = ((ComponentVersion) stack.getComponents()).imo$version();
                    StackCache cached = stacks.get(stack);
                    if (cached == null || cached.model != model || cached.version != version || (cached.batch != null && cached.batch.closed)) {
                        int hash = ItemStack.hashItemAndComponents(stack);
                        List<GpuItemBatch> bucket = buckets.get(hash);
                        GpuItemBatch batch = null;
                        if (bucket != null) {
                            for (GpuItemBatch candidate : bucket) {
                                if (candidate.model == model && ItemStack.isSameItemSameComponents(candidate.stack, stack)) {
                                    batch = candidate;
                                    break;
                                }
                            }
                        }
                        if (batch == null) {
                            try {
                                batch = new GpuItemBatch(stack, model, tintLayers, this);
                                if (bucket == null) {
                                    bucket = new ArrayList<>();
                                    buckets.put(hash, bucket);
                                }
                                bucket.add(batch);
                                modelBytes += batch.meshBytes;
                                cacheMisses++;
                            } catch (Exception exception) {
                                logger.debug("Retaining original rendering for {}: {}", stack.getItem(), exception.toString());
                            }
                        }
                        cached = new StackCache(model, version, batch);
                        stacks.put(stack, cached);
                    }
                    cached.lastFrame = frame;
                    if (cached.batch != null) {
                        Source source = sources.computeIfAbsent(first.imo$source(), key -> new Source());
                        // 先准备全部通道，再写入实例，避免只接管附魔光效的一半。
                        Submission a = source.layers.computeIfAbsent(first.imo$type(), key -> new Reference2ObjectOpenHashMap<>())
                                .computeIfAbsent(cached.batch, Submission::new);
                        Submission b = second == null ? null : source.layers.computeIfAbsent(second.imo$type(), key -> new Reference2ObjectOpenHashMap<>())
                                .computeIfAbsent(cached.batch, Submission::new);
                        if (a.count == 0) Carbon.IRIS.marker(first);
                        if (b != null && b.count == 0) Carbon.IRIS.marker(second);
                        long identifiers = Carbon.IRIS.identifiers();
                        a.add(pose.last(), light, overlay, frame, identifiers);
                        if (b != null) b.add(pose.last(), light, overlay, frame, identifiers);
                        cached.batch.lastFrame = frame;
                        instances++;
                        submitted = true;
                    }
                }
            }
        }
        return submitted;
    }

    private boolean supported(RenderType type) {
        type = Carbon.IRIS.unwrap(type);
        Boolean cached = supportedTypes.get(type);
        if (cached != null) return cached;
        boolean supported = type == RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS)
                || type == RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS)
                || type == RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS)
                || type == RenderType.itemEntityTranslucentCull(TextureAtlas.LOCATION_BLOCKS)
                || type == RenderType.glint() || type == RenderType.glintTranslucent()
                || type == RenderType.entityGlint() || type == RenderType.armorEntityGlint();
        supportedTypes.put(type, supported);
        return supported;
    }

    public void flush(MultiBufferSource.BufferSource owner, RenderType type) {
        Source source = sources.get(owner);
        var draws = source == null ? null : source.layers.get(type);
        if (draws != null && draws.values().stream().anyMatch(draw -> draw.count != 0)) {
            int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int buffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
            type.setupRenderState();
            CompiledShaderProgram shader = Carbon.IRIS.shader(RenderSystem.getShader(), type, false);
            try (MemoryStack memory = MemoryStack.stackPush()) {
                if (shader != null) {
                    shader.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
                    shader.apply();
                }
                if (!Carbon.IRIS.usesShader(shader)) {
                    FogParameters fog = RenderSystem.getShaderFog();
                    glUseProgram(program);
                    glUniformMatrix4fv(uniforms[0], false, RenderSystem.getModelViewMatrix().get(memory.mallocFloat(16)));
                    glUniformMatrix4fv(uniforms[1], false, RenderSystem.getProjectionMatrix().get(memory.mallocFloat(16)));
                    glUniformMatrix4fv(uniforms[2], false, RenderSystem.getTextureMatrix().get(memory.mallocFloat(16)));
                    glUniform1i(uniforms[3], fog.shape().getIndex());
                    if (shader != null && shader.LIGHT0_DIRECTION != null && shader.LIGHT1_DIRECTION != null) {
                        glUniform3fv(uniforms[4], shader.LIGHT0_DIRECTION.getFloatBuffer());
                        glUniform3fv(uniforms[5], shader.LIGHT1_DIRECTION.getFloatBuffer());
                    }
                    int[] units = samplerUnits.get(shader);
                    if (units == null) {
                        units = new int[]{0, 1, 2};
                        if (shader != null) {
                            for (int sampler = 0; sampler < 3; sampler++) {
                                int location = glGetUniformLocation(shader.getProgramId(), "Sampler" + sampler);
                                if (location >= 0) units[sampler] = glGetUniformi(shader.getProgramId(), location);
                            }
                        }
                        samplerUnits.put(shader, units);
                    }
                    for (int sampler = 0; sampler < 3; sampler++) glUniform1i(uniforms[6 + sampler], units[sampler]);
                    glUniform4fv(uniforms[9], RenderSystem.getShaderColor());
                    glUniform1f(uniforms[10], fog.start());
                    glUniform1f(uniforms[11], fog.end());
                    glUniform4f(uniforms[12], fog.red(), fog.green(), fog.blue(), fog.alpha());
                    glUniform1i(uniforms[13], type == RenderType.glint() || type == RenderType.glintTranslucent() || type == RenderType.entityGlint() || type == RenderType.armorEntityGlint() ? 1 : 0);
                    glUniform1f(uniforms[14], RenderSystem.getShaderGlintAlpha());
                    glUniform1i(uniforms[15], type == RenderType.itemEntityTranslucentCull(TextureAtlas.LOCATION_BLOCKS) ? 0 : 1);
                }
                for (Submission draw : draws.values()) {
                    if (draw.count > 0) {
                        if (draw.batch.spriteFrame != frame) {
                            markSprites(draw.batch.sprites);
                            draw.batch.spriteFrame = frame;
                        }
                        glBindVertexArray(draw.batch.vao);
                        if (draw.buffer == 0) draw.buffer = glGenBuffers();
                        glBindBuffer(GL_ARRAY_BUFFER, draw.buffer);
                        glBufferData(GL_ARRAY_BUFFER, draw.data.slice(0, draw.count * 128), GL_STREAM_DRAW);
                        uploadedBytes += draw.count * 128L;
                        if (Carbon.IRIS.usesShader(shader)) {
                            Carbon.IRIS.draw(shader, 0, draw.buffer, draw.batch.vertexBuffer, draw.batch.indexBuffer, draw.batch.format,
                                    draw.batch.indices, draw.count, noTransform, 0, 0, noTransform, noTransform, false);
                        } else {
                            for (int column = 0; column < 4; column++) glVertexAttribPointer(6 + column, 4, GL_FLOAT, false, 128, column * 16L);
                            for (int column = 0; column < 3; column++) glVertexAttribPointer(10 + column, 3, GL_FLOAT, false, 128, 64 + column * 12L);
                            glVertexAttribIPointer(13, 2, GL_UNSIGNED_SHORT, 128, 100);
                            glVertexAttribIPointer(14, 2, GL_UNSIGNED_SHORT, 128, 104);
                            glDrawElementsInstanced(GL_TRIANGLES, draw.batch.indices, GL_UNSIGNED_INT, 0, draw.count);
                        }
                        drawCalls++;
                        draw.count = 0;
                    }
                }
            } finally {
                if (shader != null) shader.clear();
                type.clearRenderState();
                glUseProgram(previousProgram);
                glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, buffer);
            }
        }
    }

    public void markSprites(List<net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites) {
        if (!spriteApiChecked) {
            spriteApiChecked = true;
            try {
                Class<?> api = Class.forName("net.caffeinemc.mods.sodium.api.texture.SpriteUtil");
                spriteMarker = MethodHandles.publicLookup().unreflect(api.getMethod("markSpriteActive", net.minecraft.client.renderer.texture.TextureAtlasSprite.class))
                        .bindTo(api.getField("INSTANCE").get(null));
            } catch (ReflectiveOperationException ignored) {
                spriteMarker = null;
            }
        }
        if (spriteMarker != null) {
            try {
                for (var sprite : sprites) spriteMarker.invokeExact(sprite);
            } catch (Throwable exception) {
                spriteMarker = null;
                logger.warn("Unable to mark cached item sprites active", exception);
            }
        }
    }

    public String statistics() {
        return String.format(Locale.ROOT, "%d model submissions, %d instanced draws, %d shadow instances/%d shadow draws, %d new meshes, %d instance bytes/frame, %d mesh bytes/frame", instances, drawCalls, shadowInstances, shadowDrawCalls, cacheMisses, uploadedBytes, modelBytes);
    }

    public void invalidate() {
        if (RenderSystem.isOnRenderThreadOrInit()) {
            clear();
        } else {
            RenderSystem.recordRenderCall(this::clear);
        }
    }

    private void clear() {
        for (Source source : sources.values()) {
            for (var draws : source.layers.values()) for (Submission draw : draws.values()) draw.close();
        }
        for (var bucket : buckets.values()) for (GpuItemBatch batch : bucket) batch.close();
        sources.clear();
        buckets.clear();
        stacks.clear();
        opaqueSprites.clear();
        supportedTypes.clear();
        samplerUnits.clear();
        if (program != 0) glDeleteProgram(program);
        program = 0;
        if (shadows != null) shadows.close();
        shadows = null;
        world = false;
        failed = false;
    }

    private int compile(int type, String name) throws Exception {
        int shader = glCreateShader(type);
        try (InputStream stream = getClass().getResourceAsStream("/assets/carbon/shaders/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing shader " + name);
            glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(shader));
        } catch (Exception exception) {
            glDeleteShader(shader);
            throw exception;
        }
        return shader;
    }

    public interface ComponentVersion {
        int imo$version();
        void imo$track();
        boolean imo$tracked();
    }

    public interface FoilState {
        boolean imo$foil(int tick);
    }

    public interface Target {
        MultiBufferSource.BufferSource imo$source();
        RenderType imo$type();
        void imo$target(MultiBufferSource.BufferSource source, RenderType type);
    }

    public interface VertexPair {
        VertexConsumer imo$first();
        VertexConsumer imo$second();
    }

    public interface StackHolder {
        void imo$stack(ItemStack stack);
    }

    private static final class Source {
        private final Reference2ObjectOpenHashMap<RenderType, Reference2ObjectOpenHashMap<GpuItemBatch, Submission>> layers = new Reference2ObjectOpenHashMap<>();
    }

    private static final class StackCache {
        private final BakedModel model;
        private final int version;
        private final GpuItemBatch batch;
        private int lastFrame;

        private StackCache(BakedModel model, int version, GpuItemBatch batch) {
            this.model = model;
            this.version = version;
            this.batch = batch;
        }
    }

    private static final class Submission implements AutoCloseable {
        private final GpuItemBatch batch;
        private ByteBuffer data;
        private int capacity;
        private int buffer;
        private int count;
        private int lastFrame;

        private Submission(GpuItemBatch batch) {
            this.batch = batch;
        }

        private void add(PoseStack.Pose pose, int light, int overlay, int frame, long identifiers) {
            if (count == capacity) {
                capacity = Math.max(64, capacity * 2);
                data = data == null ? MemoryUtil.memAlloc(capacity * 128) : MemoryUtil.memRealloc(data, capacity * 128);
            }
            int offset = count++ * 128;
            pose.pose().get(offset, data);
            pose.normal().get(offset + 64, data);
            data.putInt(offset + 100, light);
            data.putInt(offset + 104, overlay);
            data.putInt(offset + 108, 0);
            data.putInt(offset + 112, (int) (identifiers & 65535));
            data.putInt(offset + 116, (int) (identifiers >> 16 & 65535));
            data.putInt(offset + 120, (int) (identifiers >> 32 & 65535));
            data.putInt(offset + 124, 0);
            lastFrame = frame;
        }

        @Override
        public void close() {
            if (buffer != 0) glDeleteBuffers(buffer);
            if (data != null) MemoryUtil.memFree(data);
        }
    }
}
