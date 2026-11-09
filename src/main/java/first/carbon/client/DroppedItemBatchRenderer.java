package first.carbon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import first.carbon.Carbon;
import first.carbon.client.ItemBatchRenderer.ComponentVersion;
import first.carbon.client.ItemBatchRenderer.FoilState;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.item.Item;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.Vec3;
import com.mojang.math.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.opengl.GL33.*;

public final class DroppedItemBatchRenderer {
    private final Logger logger = LogManager.getLogger("Carbon");
    private final Int2ObjectOpenHashMap<List<GpuDroppedItemBatch>> buckets = new Int2ObjectOpenHashMap<>();
    private final IdentityHashMap<Class<?>, Boolean> dynamicFoil = new IdentityHashMap<>();
    // Item.isFoil 的运行时名称（生产环境为 intermediary 名）
    private final String isFoilName = net.fabricmc.loader.api.FabricLoader.getInstance().getMappingResolver()
            .mapMethodName("intermediary", "net.minecraft.class_1792", "method_7886", "(Lnet/minecraft/class_1799;)Z");
    private final Set<BakedModel> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
    final IdentityHashMap<net.minecraft.client.renderer.texture.TextureAtlasSprite, Boolean> opaqueSprites = new IdentityHashMap<>();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f sceneView = new Matrix4f();
    private final Matrix4f sceneProjection = new Matrix4f();
    private final Matrix4f transform = new Matrix4f();
    private final BlockPos.MutableBlockPos shadowPosition = new BlockPos.MutableBlockPos();
    private final Long2IntOpenHashMap lightCells = new Long2IntOpenHashMap();
    private final List<Entity> remainingEntities = new ArrayList<>();
    private final List<Entity> remainingView = Collections.unmodifiableList(remainingEntities);
    private final List<ItemEntity> updateQueue = new ArrayList<>();
    private List<ItemEntity> nbtItems = new ArrayList<>(), updateNbtItems = new ArrayList<>();
    private int nbtTick = Integer.MIN_VALUE;
    private Set<ItemEntity> remainingItems = Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<ItemEntity> updateRemainingItems = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean updating, updateStationary, updateTickStable;
    private int updateIndex, updateInstances, updateCollected;
    private double updateDistanceMargin;
    private float partialTick;
    private List<?> sceneEntities;
    public int collected;
    public volatile boolean sceneDirty = true;
    public volatile boolean blockCacheDirty = true;
    private boolean sceneValid;
    private boolean sceneStationary;
    private boolean sceneReused;
    private boolean sceneTickStable;
    private long sceneTick;
    private long sceneFoilTick;
    private int sceneSkyDarken;
    private int sceneEntityCount;
    private int sceneInstances;
    private int sceneCollected;
    private Vec3 sceneCamera = Vec3.ZERO;
    private Entity sceneCameraEntity;
    private double sceneDistanceMargin, sceneViewScale, sceneEntityDistance;
    private int sceneRenderDistance;
    private long sceneBuilds;
    private long sceneReuses;
    private boolean sceneShadows;
    private boolean sceneSkipVisibilityChecks;
    private ClientLevel level;
    private Vec3 camera = Vec3.ZERO;
    private double originX;
    private double originY;
    private double originZ;
    private float clock;
    private int ticks;
    private long foilTick;
    private int program;
    private int entityProgram;
    private int preparedProgram;
    private GpuDroppedInstanceProcessor processor;
    private GpuShadowBatch shadows;
    private int generation;
    private int frame;
    private boolean active;
    private boolean failed;
    private boolean enabled;
    private int irisGeneration = -1;
    private final com.mojang.math.Vector3f irisCamera = new com.mojang.math.Vector3f();
    boolean physics;
    boolean capturing;
    public long uploadedBytes;
    public long modelBytes;
    public int instances;
    public int drawCalls;
    public int cacheMisses;
    public int shadowInstances;
    public int shadowDrawCalls;
    public int transformDispatches;
    public long cacheUpdateNs;
    public int pendingUpdates, updatedEntities;

    public void beginFrame(Camera camera, Matrix4f view, Matrix4f projection, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (irisGeneration != Carbon.Client.IRIS.generation) {
            clear();
            irisGeneration = Carbon.Client.IRIS.generation;
        }
        boolean enabled = Carbon.Client.IRIS.canInstance() && Carbon.CONFIG.enabled.get() && Carbon.CONFIG.optimizeDroppedItems.get();
        boolean physics = enabled && Carbon.CONFIG.physicalDroppedItems.get();
        if (this.enabled != enabled || this.physics != physics) {
            clear();
            this.enabled = enabled;
            this.physics = physics;
        }
        this.camera = camera.getPosition();
        if (level != minecraft.level || Math.abs(this.camera.x - originX) > 1024
                || Math.abs(this.camera.y - originY) > 1024 || Math.abs(this.camera.z - originZ) > 1024) {
            clear();
            level = minecraft.level;
            originX = Math.floor(this.camera.x / 256) * 256;
            originY = Math.floor(this.camera.y / 256) * 256;
            originZ = Math.floor(this.camera.z / 256) * 256;
        }
        irisCamera.set((float) (this.camera.x - originX), (float) (this.camera.y - originY), (float) (this.camera.z - originZ));
        this.view.load(view);
        this.projection.load(projection);
        this.partialTick = partialTick;
        ticks = level == null ? 0 : (int) (level.getGameTime() % 24000);
        foilTick = minecraft.gui.getGuiTicks();
        clock = ticks + partialTick;
        frame++;
        uploadedBytes = 0;
        modelBytes = 0;
        instances = 0;
        collected = 0;
        drawCalls = 0;
        cacheMisses = 0;
        transformDispatches = 0;
        shadowDrawCalls = 0;
        cacheUpdateNs = 0;
        updatedEntities = 0;
        active = false;
        if (!failed && enabled && GL.getCapabilities().OpenGL33) {
            if (program == 0) {
                int fragment = 0;
                int shadowFragment = 0;
                try {
                    fragment = compile(GL_FRAGMENT_SHADER, "dropped_instanced.fsh");
                    program = createProgram("dropped_instanced.vsh", fragment);
                    entityProgram = createProgram(physics ? "physics_instanced.vsh" : "entity_instanced.vsh", fragment);
                    shadowFragment = compile(GL_FRAGMENT_SHADER, "shadow.fsh");
                    shadows = new GpuShadowBatch(createProgram("dropped_shadow.vsh", shadowFragment));
                    if (GL.getCapabilities().OpenGL43) {
                        try {
                            preparedProgram = createProgram(physics ? "physics_prepared.vsh" : "entity_prepared.vsh", fragment);
                            processor = new GpuDroppedInstanceProcessor(physics);
                        } catch (Exception exception) {
                            if (preparedProgram != 0) glDeleteProgram(preparedProgram);
                            preparedProgram = 0;
                            logger.warn("Compute animation unavailable; using vertex animation", exception);
                        }
                    }
                    logger.info("GPU item instancing ready: {} / {}", glGetString(GL_RENDERER), glGetString(GL_VERSION));
                } catch (Exception exception) {
                    if (program != 0) {
                        glDeleteProgram(program);
                        program = 0;
                    }
                    if (entityProgram != 0) {
                        glDeleteProgram(entityProgram);
                        entityProgram = 0;
                    }
                    failed = true;
                    logger.error("Instancing unavailable; retaining vanilla rendering", exception);
                } finally {
                    if (fragment != 0) {
                        glDeleteShader(fragment);
                    }
                    if (shadowFragment != 0) glDeleteShader(shadowFragment);
                }
            }
            active = program != 0 && entityProgram != 0;
        }
        boolean skipVisibility = Carbon.CONFIG.skipVisibilityChecks.get();
        boolean dedicated = !minecraft.getEntityRenderDispatcher().shouldRenderHitBoxes() && !Carbon.Client.IRIS.shadowPass();
        if (!dedicated) {
            updating = false;
            updateQueue.clear();
            pendingUpdates = 0;
            sceneValid = false;
            sceneDirty = true;
        }
        if (updating && (!this.camera.equals(sceneCamera) || !this.view.equals(sceneView) || !projection.equals(sceneProjection))) {
            sceneDirty = true;
        }
        sceneReused = active && dedicated && !updating
                && (minecraft.options.entityShadows && ((ShadowSettings) minecraft.getEntityRenderDispatcher()).imo$shadows()) == sceneShadows
                && skipVisibility == sceneSkipVisibilityChecks
                && (skipVisibility || this.camera.equals(sceneCamera) && this.view.equals(sceneView) && projection.equals(sceneProjection))
                && sceneValid && sceneStationary && !sceneDirty && !blockCacheDirty && level.getSkyDarken() == sceneSkyDarken
                && minecraft.getCameraEntity() == sceneCameraEntity
                && (sceneTickStable || level.getGameTime() == sceneTick && foilTick == sceneFoilTick)
                && level.getGameTime() / 24000 == sceneTick / 24000
                && level.getEntityCount() == sceneEntityCount && Entity.getViewScale() == sceneViewScale
                && minecraft.options.renderDistance == sceneRenderDistance
                && minecraft.options.entityDistanceScaling == sceneEntityDistance
                && camera.getPosition().distanceToSqr(sceneCamera) < sceneDistanceMargin * sceneDistanceMargin
                && !level.getChunkSource().getLightEngine().hasLightWork();
        if (sceneReused && !nbtItems.isEmpty() && nbtTick != minecraft.gui.getGuiTicks()) {
            nbtTick = minecraft.gui.getGuiTicks();
            for (ItemEntity item : nbtItems) {
                EntityCache cached = ((EntityCacheAccess) item).imo$cache();
                if (cached.version != ((ComponentVersion) (Object) cached.stack).imo$version()) {
                    sceneReused = false;
                    break;
                }
            }
        }
        boolean retained = active && dedicated && sceneValid;
        if (retained) {
            instances = sceneInstances;
            collected = sceneCollected;
        }
        if (shadows != null) {
            if (!sceneValid) shadows.retainedCount = 0;
            shadows.count = retained ? shadows.retainedCount : 0;
            shadows.flushed = false;
        }
        if (blockCacheDirty) {
            lightCells.clear();
            lightCells.defaultReturnValue(-1);
            if (shadows != null) shadows.receivers.clear();
            blockCacheDirty = false;
        } else if (shadows != null && level != null && level.getSkyDarken() != sceneSkyDarken) {
            shadows.receivers.clear();
        }
        shadowInstances = shadows == null ? 0 : shadows.count;
        for (List<GpuDroppedItemBatch> bucket : buckets.values()) {
            for (GpuDroppedItemBatch batch : bucket) {
                if (!sceneValid) batch.retainedCount = 0;
                batch.count = retained && batch.entityBatch ? batch.retainedCount : 0;
                if (!retained) batch.physicsMoving = false;
                if (batch.count > 0 || updating && batch.updateCount > 0) batch.lastFrame = frame;
                batch.flushed = false;
                batch.processed = false;
            }
        }
    }

    public boolean submit(ItemStack stack, net.minecraft.client.renderer.block.model.ItemTransforms.TransformType context, boolean leftHand, PoseStack pose,
                          int light, int overlay, BakedModel model) {
        boolean submitted = false;
        if (active && !capturing && context == ItemTransforms.TransformType.GROUND && !leftHand
                && overlay == OverlayTexture.NO_OVERLAY) {
            GpuDroppedItemBatch batch = find(stack, model, false);
            if (batch != null && !batch.flushed && batch.count < 65536) {
                transform.load(pose.last().pose());
                transform.multiplyWithTranslation((float) (camera.x - originX), (float) (camera.y - originY), (float) (camera.z - originZ));
                batch.add(transform, pose.last().normal(), 0, 0, false, light, false, 0, 0, 0);
                batch.lastFrame = frame;
                instances++;
                submitted = true;
            }
        }
        return submitted;
    }

    public boolean submitEntity(ItemEntity entity, float partialTick, int light, PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers) {
        boolean submitted = false;
        boolean shadow = Carbon.Client.IRIS.shadowPass();
        if (active && !capturing && entity.level == level && level.getEntity(entity.getId()) == entity
                && !entity.isCurrentlyGlowing() && (!shadow || physics)) {
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) {
                return false;
            }
            int version = ((ComponentVersion) (Object) stack).imo$version();
            EntityCache cached = ((EntityCacheAccess) entity).imo$cache();
            var itemRenderer = Minecraft.getInstance().getItemRenderer();
            if (cached.stack != stack || cached.version != version || cached.generation != generation
                    || (cached.batch != null && cached.batch.closed)) {
                cached.stack = stack;
                cached.version = version;
                cached.generation = generation;
                cached.batch = null;
                cached.motionTick = Long.MIN_VALUE;
                cached.roll = 0;
                cached.rate = 0;
                BakedModel base = itemRenderer.getItemModelShaper().getItemModel(stack);
                cached.dynamicModel = base.getClass() == SimpleBakedModel.class
                        && base.getOverrides() != net.minecraft.client.renderer.block.model.ItemOverrides.EMPTY;
                if (base.getClass() == SimpleBakedModel.class && !cached.dynamicModel) {
                    cached.batch = find(stack, base, true);
                }
                cached.dynamicFoil = dynamicFoil.getOrDefault(stack.getItem().getClass(), true);
            }
            if (cached.batch == null && !cached.dynamicModel && cached.dynamicFoil) {
                BakedModel model = itemRenderer.getModel(stack, level, null, entity.getId());
                cached.batch = find(stack, model, true);
            }
            if (cached.dynamicModel) {
                cached.batch = find(stack, itemRenderer.getModel(stack, level, null, entity.getId()), true);
            }
            GpuDroppedItemBatch batch = cached.batch;
            if (batch != null && cached.dynamicFoil) {
                batch = find(stack, batch.model, true);
                cached.batch = batch;
            }
            if (batch != null && (shadow || !batch.flushed)) {
                ((ComponentVersion) (Object) stack).imo$track();
                int count = stack.getCount();
                int copies = count <= 1 ? 1 : count <= 16 ? 2 : count <= 32 ? 3 : count <= 48 ? 4 : 5;
                if (shadow || batch.count + copies <= 65536) {
                    // 1.20.1 原版掉落物始终散布与浮动，无开关字段。
                    boolean spread = true;
                    boolean bob = true;
                    float ageOffset = entity.getAge() - (float) ticks;
                    if (physics && cached.motionTick != level.getGameTime()) {
                        if (cached.motionTick != Long.MIN_VALUE) {
                            cached.roll = (cached.roll + cached.rate * Math.min(20, level.getGameTime() - cached.motionTick)) % (float) (Math.PI * 2);
                        }
                        double dx = entity.getX() - entity.xOld;
                        double dy = entity.getY() - entity.yOld;
                        double dz = entity.getZ() - entity.zOld;
                        double speed = dx * dx + dy * dy + dz * dz;
                        cached.rate = !entity.isOnGround() && speed > 1.0E-8 ? (float) Math.min(0.8, Math.sqrt(speed) * 4) : 0;
                        if (entity.isInWater()) cached.rate *= 0.35f;
                        if (entity.isInLava()) cached.rate *= 0.15f;
                        if (entity.isOnGround() && !batch.gui3d) cached.roll = 0;
                        cached.motionTick = level.getGameTime();
                    }
                    if (shadow) {
                        var rotation = new com.mojang.math.Quaternion(com.mojang.math.Vector3f.YP, -entity.getYRot() * Mth.DEG_TO_RAD, true);
                        if (batch.flat) rotation.mul(new com.mojang.math.Quaternion(com.mojang.math.Vector3f.XP, (float) (Math.PI / 2), true));
                        rotation.mul(new com.mojang.math.Quaternion(com.mojang.math.Vector3f.YP, cached.roll + cached.rate * (clock - (float) Math.floor(clock)), true));
                        var matrix = new com.mojang.math.Matrix3f(rotation);
                        float height = Math.abs(store3f(matrix, 0, 1)) * batch.halfSize.x() + Math.abs(store3f(matrix, 1, 1)) * batch.halfSize.y() + Math.abs(store3f(matrix, 2, 1)) * batch.halfSize.z();
                        var center = batch.center.copy();
                        center.transform(rotation);
                        var offset = new com.mojang.math.Vector3f();
                        capturing = true;
                        try {
                            for (int copy = 0; copy < copies; copy++) {
                                int index = copy * 3;
                                offset.set(spread ? batch.copyOffsets[index] : 0, spread ? batch.copyOffsets[index + 1] : 0, batch.gui3d && spread ? batch.copyOffsets[index + 2] : 0);
                                offset.transform(rotation);
                                offset.set(offset.x(), height + (batch.gui3d ? Math.abs(offset.y()) : copy * (height * 2 + 0.002f)) + 0.002f, offset.z());
                                offset.sub(center);
                                pose.pushPose();
                                pose.translate(offset.x(), offset.y() + ((entity.getId() * 0x9E3779B9) >>> 16) * (0.003f / 65535), offset.z());
                                pose.mulPose(rotation);
                                itemRenderer.render(stack, ItemTransforms.TransformType.GROUND, false, pose, buffers, light, OverlayTexture.NO_OVERLAY, batch.model);
                                pose.popPose();
                            }
                        } finally { capturing = false; }
                    } else {
                        batch.addEntity((float) (Mth.lerp(partialTick, entity.xOld, entity.getX()) - originX),
                                (float) (Mth.lerp(partialTick, entity.yOld, entity.getY()) - originY)
                                        + (physics ? ((entity.getId() * 0x9E3779B9) >>> 16) * (0.003f / 65535) : 0),
                                (float) (Mth.lerp(partialTick, entity.zOld, entity.getZ()) - originZ),
                                physics ? cached.roll : ageOffset, physics ? -entity.getYRot() * Mth.DEG_TO_RAD : entity.bobOffs, light, bob, spread, copies,
                                cached.rate);
                        instances += copies;
                    }
                    batch.lastFrame = frame;
                    submitted = true;
                }
            }
        }
        return submitted;
    }

    public void addShadow(ItemEntity entity, float partialTick, boolean enabled) {
        if (enabled && !Carbon.Client.IRIS.active() && shadows != null && !shadows.flushed) {
            shadows.add(entity, partialTick, 0, originX, originY, originZ);
            shadowInstances = shadows.count;
        }
    }

    public Iterable<Entity> collect(Iterable<Entity> entities) {
        if (!active) return entities;
        var minecraft = Minecraft.getInstance();
        var dispatcher = minecraft.getEntityRenderDispatcher();
        if (dispatcher.shouldRenderHitBoxes() || Carbon.Client.IRIS.shadowPass()) return entities;
        if ((entities instanceof List<?> || sceneEntities != null) && !entities.equals(sceneEntities)) {
            sceneReused = false;
            updating = false;
            updateQueue.clear();
        }
        if (sceneReused) {
            sceneReuses++;
            return remainingView;
        }
        long start = System.nanoTime();
        long budget = (long) (Carbon.CONFIG.cacheUpdateBudgetMs.get() * 1_000_000);
        boolean starting = !updating;
        boolean rendererChecked = false;
        boolean standardRenderer = false;
        boolean renderShadows = updating ? sceneShadows : minecraft.options.entityShadows && ((ShadowSettings) dispatcher).imo$shadows();
        boolean skipVisibility = updating ? sceneSkipVisibilityChecks : Carbon.CONFIG.skipVisibilityChecks.get();
        if (starting) {
            sceneEntities = entities instanceof List<?> list ? list : null;
            updating = true;
            updateIndex = updateInstances = updateCollected = 0;
            updateStationary = updateTickStable = true;
            updateDistanceMargin = Double.POSITIVE_INFINITY;
            updateRemainingItems.clear();
            updateNbtItems.clear();
            sceneDirty = false;
            for (Entity entity : entities) {
                if (entity instanceof ItemEntity item) updateQueue.add(item);
            }
            sceneTick = level.getGameTime();
            sceneFoilTick = foilTick;
            sceneSkyDarken = level.getSkyDarken();
            sceneEntityCount = level.getEntityCount();
            sceneCamera = camera;
            sceneCameraEntity = minecraft.getCameraEntity();
            sceneView.load(view);
            sceneProjection.load(projection);
            sceneViewScale = Entity.getViewScale();
            sceneRenderDistance = minecraft.options.renderDistance;
            sceneEntityDistance = minecraft.options.entityDistanceScaling;
            sceneShadows = renderShadows;
            sceneSkipVisibilityChecks = skipVisibility;
        }
        for (List<GpuDroppedItemBatch> bucket : buckets.values()) {
            for (GpuDroppedItemBatch batch : bucket) {
                if (batch.entityBatch) {
                    if (starting) batch.updateCount = 0;
                    batch.swapUpdate();
                    if (starting) batch.physicsMoving = false;
                }
            }
        }
        if (shadows != null) {
            if (starting) shadows.updateCount = 0;
            shadows.swapUpdate();
        }
        instances = updateInstances;
        collected = updateCollected;
        while (updateIndex < updateQueue.size() && (budget == 0 || updatedEntities == 0 || System.nanoTime() - start < budget)) {
            ItemEntity item = updateQueue.get(updateIndex++);
            updatedEntities++;
            boolean submitted = false;
            boolean culled = item.isRemoved();
            if (!culled && item.getClass() == ItemEntity.class && item.getType() == EntityType.ITEM
                    && item != minecraft.getCameraEntity() && ((EntityCacheAccess) item).imo$eligible()) {
                if (!rendererChecked) {
                    standardRenderer = dispatcher.getRenderer(item).getClass() == ItemEntityRenderer.class;
                    rendererChecked = true;
                }
                if (standardRenderer) {
                    if (skipVisibility) {
                        double size = item.getBoundingBox().getSize();
                        if (Double.isNaN(size)) size = 1;
                        updateDistanceMargin = Math.min(updateDistanceMargin, Math.abs(size * 64 * Entity.getViewScale() - item.position().distanceTo(camera)));
                    }
                    culled = skipVisibility ? !item.shouldRender(camera.x, camera.y, camera.z)
                            : !dispatcher.shouldRender(item, ((first.carbon.mixin.LevelRendererAccessor) minecraft.levelRenderer).carbon$frustum(), camera.x, camera.y, camera.z);
                    if (!culled) {
                        Vec3 probe = item.getLightProbePosition(partialTick);
                        shadowPosition.set(Mth.floor(probe.x), Mth.floor(probe.y), Mth.floor(probe.z));
                        long key = shadowPosition.asLong();
                        int light = lightCells.get(key);
                        if (light == -1) {
                            light = LightTexture.pack(level.getBrightness(LightLayer.BLOCK, shadowPosition), level.getBrightness(LightLayer.SKY, shadowPosition));
                            lightCells.put(key, light);
                        }
                        if (((EntityCacheAccess) item).imo$hasFire()) light = (light & 0xFFFF0000) | 240;
                        submitted = submitEntity(item, partialTick, light, null, null);
                        if (submitted) {
                            collected++;
                            if (item.getItem().getTag() != null) updateNbtItems.add(item);
                            addShadow(item, partialTick, renderShadows);
                            EntityCache cached = ((EntityCacheAccess) item).imo$cache();
                            updateStationary &= item.xOld == item.getX() && item.yOld == item.getY() && item.zOld == item.getZ()
                                    && (!physics || cached.rate == 0)
                                    && !cached.dynamicModel;
                            updateTickStable &= !cached.dynamicFoil && (physics || item.getAge() != -32768);
                        }
                    }
                }
            }
            if (!submitted && !culled) {
                updateRemainingItems.add(item);
                updateStationary = false;
            }
        }
        updateInstances = instances;
        updateCollected = collected;
        pendingUpdates = updateQueue.size() - updateIndex;
        updating = pendingUpdates > 0;
        for (List<GpuDroppedItemBatch> bucket : buckets.values()) {
            for (GpuDroppedItemBatch batch : bucket) {
                if (batch.entityBatch) {
                    if (updating) batch.swapUpdate();
                    else batch.retainedCount = batch.count;
                    if (batch.count > 0 || batch.updateCount > 0) batch.lastFrame = frame;
                }
            }
        }
        if (shadows != null) {
            if (updating) shadows.swapUpdate();
            else shadows.retainedCount = shadows.count;
        }
        shadowInstances = shadows == null ? 0 : shadows.count;
        if (updating) {
            instances = sceneValid ? sceneInstances : 0;
            collected = sceneValid ? sceneCollected : 0;
        } else {
            sceneValid = true;
            sceneBuilds++;
            sceneStationary = updateStationary;
            sceneTickStable = updateTickStable;
            sceneDistanceMargin = updateDistanceMargin;
            sceneInstances = instances;
            sceneCollected = collected;
            Set<ItemEntity> remaining = remainingItems;
            remainingItems = updateRemainingItems;
            updateRemainingItems = remaining;
            List<ItemEntity> previousNbt = nbtItems;
            nbtItems = updateNbtItems;
            updateNbtItems = previousNbt;
            updateQueue.clear();
        }
        cacheUpdateNs = System.nanoTime() - start;
        remainingEntities.clear();
        for (Entity entity : entities) {
            if (!(entity instanceof ItemEntity) || remainingItems.contains(entity)) remainingEntities.add(entity);
        }
        return remainingView;
    }

    private GpuDroppedItemBatch find(ItemStack stack, BakedModel model, boolean entityBatch) {
        if (stack.isEmpty()) {
            return null;
        }
        boolean foil = ((FoilState) (Object) stack).imo$foil((int) foilTick);
        GpuDroppedItemBatch result = null;
        if (model.getClass() == SimpleBakedModel.class && !rejected.contains(model)
                && !stack.is(Items.TRIDENT) && !stack.is(Items.SPYGLASS)
                && !(foil && (stack.getItem() == Items.COMPASS || stack.getItem() == Items.CLOCK))) {
            dynamicFoil.computeIfAbsent(stack.getItem().getClass(), type -> {
                try {
                    return type.getMethod(isFoilName, ItemStack.class).getDeclaringClass() != Item.class;
                } catch (ReflectiveOperationException exception) {
                    return true;
                }
            });
            int hash = (31 * stack.getItem().hashCode() + (stack.getTag() == null ? 0 : stack.getTag().hashCode()));
            List<GpuDroppedItemBatch> bucket = buckets.get(hash);
            if (bucket != null) {
                for (GpuDroppedItemBatch candidate : bucket) {
                    if (candidate.model == model && candidate.entityBatch == entityBatch && candidate.foil == foil && ItemStack.isSameItemSameTags(candidate.stack, stack)) {
                        result = candidate;
                        break;
                    }
                }
            }
            if (result == null) {
                // 原版无 getRenderPasses/getRenderTypes：仅接受标准实体通道的普通模型。
                RenderType type = net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderType(stack, true);
                boolean supported = type == RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS) || type == RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS) || type == RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS) || type == RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS) || type == RenderType.itemEntityTranslucentCull(TextureAtlas.LOCATION_BLOCKS);
                if (supported) {
                    try {
                        result = new GpuDroppedItemBatch(stack, model, this, entityBatch, foil);
                        if (bucket == null) {
                            bucket = new ArrayList<>();
                            buckets.put(hash, bucket);
                        }
                        bucket.add(result);
                        cacheMisses++;
                        modelBytes += result.meshBytes;
                    } catch (Exception exception) {
                        rejected.add(model);
                        logger.warn("Keeping vanilla renderer for {}: {}", stack.getItem(), exception.toString());
                    }
                } else {
                    rejected.add(model);
                }
            }
        }
        if (result != null) result.lastFrame = frame;
        return result;
    }

    public void flush() {
        if (active) {
            int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int buffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
            try (MemoryStack memory = MemoryStack.stackPush()) {
                for (List<GpuDroppedItemBatch> bucket : buckets.values()) {
                    for (GpuDroppedItemBatch batch : bucket) {
                        if (batch.count > 0 && !batch.flushed) {
                            batch.processed = batch.entityBatch && processor != null;
                            int drawProgram = batch.entityBatch ? batch.processed ? preparedProgram : entityProgram : program;
                            uploadedBytes += batch.upload();
                            if (batch.processed && processor.prepare(batch, clock)) transformDispatches++;
                            Carbon.Client.RENDERER.markSprites(batch.sprites);
                            for (int pass = 0; pass < (batch.foil ? 2 : 1); pass++) {
                                for (GpuDroppedItemBatch.Mesh mesh : batch.meshes) {
                                    RenderType type = pass == 0 ? mesh.type : RenderType.glint();
                                    type.setupRenderState();
                                    try {
                                        ShaderInstance vanilla = Carbon.Client.IRIS.shader(RenderSystem.getShader(), type, true);
                                        if (vanilla != null) {
                                            if (vanilla.MODEL_VIEW_MATRIX != null) vanilla.MODEL_VIEW_MATRIX.set(view); if (vanilla.PROJECTION_MATRIX != null) vanilla.PROJECTION_MATRIX.set(projection);
                                            RenderSystem.setupShaderLights(vanilla);
                                            for (int sampler = 0; sampler < 3; sampler++) {
                                                vanilla.setSampler("Sampler" + sampler, RenderSystem.getShaderTexture(sampler));
                                            }
                                            Carbon.Client.IRIS.apply(vanilla);
                                        }
                                        if (Carbon.Client.IRIS.usesShader(vanilla)) {
                                            int mode = batch.processed ? batch.physics ? 2 : 1 : batch.physics ? 4 : 3;
                                            Carbon.Client.IRIS.draw(vanilla, mode, batch.processed ? batch.preparedBuffer : batch.instanceBuffer,
                                                    mesh.vertexBuffer, mesh.indexBuffer, mesh.format, mesh.indices, batch.count,
                                                    irisCamera, clock, batch.groundScaleY, batch.center, batch.halfSize, batch.flat);
                                        } else {
                                            glUseProgram(drawProgram);
                                            glUniform1i(glGetUniformLocation(drawProgram, "GlintPass"), pass);
                                            FloatBuffer matData = memory.mallocFloat(16);
                                            RenderSystem.getTextureMatrix().store(matData);
                                            glUniformMatrix4fv(glGetUniformLocation(drawProgram, "TextureMat"), false, matData);
                                            glUniform1f(glGetUniformLocation(drawProgram, "GlintAlpha"), 1.0f);
                                            view.store(matData);
                                            glUniformMatrix4fv(glGetUniformLocation(drawProgram, "View"), false, matData);
                                            projection.store(matData);
                                            glUniformMatrix4fv(glGetUniformLocation(drawProgram, "Projection"), false, matData);
                                            glUniform3f(glGetUniformLocation(drawProgram, "Camera"), (float) (camera.x - originX),
                                                    (float) (camera.y - originY), (float) (camera.z - originZ));
                                            glUniform1f(glGetUniformLocation(drawProgram, "Clock"), clock);
                                            glUniform1f(glGetUniformLocation(drawProgram, "GroundScaleY"), batch.groundScaleY);
                                            if (batch.physics) {
                                                glUniform3f(glGetUniformLocation(drawProgram, "Center"), batch.center.x(), batch.center.y(), batch.center.z());
                                                glUniform3f(glGetUniformLocation(drawProgram, "HalfSize"), batch.halfSize.x(), batch.halfSize.y(), batch.halfSize.z());
                                                glUniform1i(glGetUniformLocation(drawProgram, "Flat"), batch.flat ? 1 : 0);
                                            }
                                            for (int sampler = 0; sampler < 3; sampler++) {
                                                glUniform1i(glGetUniformLocation(drawProgram, "Sampler" + sampler), sampler);
                                            }
                                            float[] fog = RenderSystem.getShaderFogColor();
                                            glUniform4f(glGetUniformLocation(drawProgram, "FogColor"), fog[0], fog[1], fog[2], fog[3]);
                                            float[] color = RenderSystem.getShaderColor();
                                            glUniform4f(glGetUniformLocation(drawProgram, "ColorModulator"), color[0], color[1], color[2], color[3]);
                                            glUniform1f(glGetUniformLocation(drawProgram, "FogStart"), RenderSystem.getShaderFogStart());
                                            glUniform1f(glGetUniformLocation(drawProgram, "FogEnd"), RenderSystem.getShaderFogEnd());
                                            
                                            if (vanilla != null && vanilla.LIGHT0_DIRECTION != null && vanilla.LIGHT1_DIRECTION != null) {
                                                glUniform3fv(glGetUniformLocation(drawProgram, "Light0"), vanilla.LIGHT0_DIRECTION.getFloatBuffer());
                                                glUniform3fv(glGetUniformLocation(drawProgram, "Light1"), vanilla.LIGHT1_DIRECTION.getFloatBuffer());
                                            }
                                            mesh.bind();
                                            glDrawElementsInstanced(GL_TRIANGLES, mesh.indices, GL_UNSIGNED_INT, 0, batch.count);
                                        }
                                        drawCalls++;
                                        if (vanilla != null) {
                                            vanilla.clear();
                                        }
                                    } finally {
                                        type.clearRenderState();
                                    }
                                }
                            }
                            batch.flushed = true;
                        }
                    }
                }
                if (shadows != null && shadows.count > 0 && !shadows.flushed) {
                    uploadedBytes += shadows.draw(view, projection, camera, originX, originY, originZ);
                    shadowDrawCalls++;
                }
            } finally {
                Carbon.Client.IRIS.restoreProgram(previousProgram);
                glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, buffer);
            }
        }
    }

    public void endFrame() {
        active = false;
        long allocated = 0;
        int count = 0;
        for (List<GpuDroppedItemBatch> bucket : buckets.values()) {
            for (GpuDroppedItemBatch batch : bucket) {
                allocated += batch.meshBytes + (batch.capacity + batch.updateCapacity) * (long) batch.instanceStride + batch.preparedCapacity * 40L;
                count++;
            }
        }
        Iterator<List<GpuDroppedItemBatch>> bucketsIterator = buckets.values().iterator();
        while (bucketsIterator.hasNext()) {
            List<GpuDroppedItemBatch> bucket = bucketsIterator.next();
            Iterator<GpuDroppedItemBatch> iterator = bucket.iterator();
            while (iterator.hasNext()) {
                GpuDroppedItemBatch batch = iterator.next();
                if (frame - batch.lastFrame > 300 || (batch.lastFrame != frame && (allocated > 67108864 || count > 256))) {
                    allocated -= batch.meshBytes + (batch.capacity + batch.updateCapacity) * (long) batch.instanceStride + batch.preparedCapacity * 40L;
                    count--;
                    batch.close();
                    iterator.remove();
                }
            }
            if (bucket.isEmpty()) {
                bucketsIterator.remove();
            }
        }
    }

    public String statistics() {
        return String.format(Locale.ROOT, "%d instances (%d collected), %d instanced draws, %d shadow instances/%d shadow draws, %d new meshes, %d instance bytes/frame, %d mesh bytes/frame, %d scene builds/%d reuses, %d transform dispatches, %d queued/%d updated, %.3f ms cache update",
                instances, collected, drawCalls, shadowInstances, shadowDrawCalls, cacheMisses, uploadedBytes, modelBytes, sceneBuilds, sceneReuses, transformDispatches, pendingUpdates, updatedEntities, cacheUpdateNs / 1_000_000.0);
    }

    public void invalidate() {
        if (RenderSystem.isOnRenderThreadOrInit()) {
            clear();
        } else {
            RenderSystem.recordRenderCall(this::clear);
        }
    }

    public boolean capturingModel() {
        return capturing;
    }

    private void clear() {
        active = false;
        generation++;
        for (List<GpuDroppedItemBatch> bucket : buckets.values()) {
            for (GpuDroppedItemBatch batch : bucket) {
                batch.close();
            }
        }
        buckets.clear();
        dynamicFoil.clear();
        rejected.clear();
        opaqueSprites.clear();
        lightCells.clear();
        remainingEntities.clear();
        updateQueue.clear();
        remainingItems.clear();
        updateRemainingItems.clear();
        nbtItems.clear();
        updateNbtItems.clear();
        updating = false;
        pendingUpdates = updateIndex = 0;
        sceneInstances = sceneCollected = 0;
        sceneEntities = null;
        sceneValid = false;
        sceneCameraEntity = null;
        sceneDirty = true;
        blockCacheDirty = true;
        sceneBuilds = 0;
        sceneReuses = 0;
        if (program != 0) {
            glDeleteProgram(program);
            program = 0;
        }
        if (entityProgram != 0) {
            glDeleteProgram(entityProgram);
            entityProgram = 0;
        }
        if (processor != null) {
            processor.close();
            processor = null;
        }
        if (shadows != null) {
            shadows.close();
            shadows = null;
        }
        if (preparedProgram != 0) {
            glDeleteProgram(preparedProgram);
            preparedProgram = 0;
        }
        failed = false;
    }

    private int compile(int type, String name) throws IOException {
        String source;
        try (InputStream stream = getClass().getResourceAsStream("/assets/carbon/shaders/" + name)) {
            if (stream == null) {
                throw new IOException("Missing shader " + name);
            }
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IOException(error);
        }
        return shader;
    }

    private int createProgram(String name, int fragment) throws IOException {
        int vertex = compile(GL_VERTEX_SHADER, name);
        int result = glCreateProgram();
        try {
            glAttachShader(result, vertex);
            glAttachShader(result, fragment);
            glLinkProgram(result);
            if (glGetProgrami(result, GL_LINK_STATUS) == GL_FALSE) {
                throw new IOException(glGetProgramInfoLog(result));
            }
        } catch (IOException exception) {
            glDeleteProgram(result);
            throw exception;
        } finally {
            glDeleteShader(vertex);
        }
        return result;
    }

    public interface ShadowSettings {
        boolean imo$shadows();
    }

    public interface EntityCacheAccess {
        EntityCache imo$cache();
        boolean imo$eligible();
        boolean imo$hasFire();
        void imo$invalidate();
    }

    public static final class EntityCache {
        private ItemStack stack;
        private GpuDroppedItemBatch batch;
        private int version;
        private int generation = -1;
        private boolean dynamicModel;
        private boolean dynamicFoil;
        private long motionTick = Long.MIN_VALUE;
        private float roll, rate;
    }

    // Matrix3f 字段 protected，用一次 store 读取指定行列元素（store 为行主序）
    private static float store3f(com.mojang.math.Matrix3f matrix, int row, int column) {
        java.nio.FloatBuffer buffer = java.nio.FloatBuffer.allocate(9);
        matrix.store(buffer);
        return buffer.get(row * 3 + column);
    }

}
