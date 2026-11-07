package first.carbon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import first.carbon.Carbon;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void carbon$matrices(com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline, Camera camera, org.joml.Matrix4f view,
                                 org.joml.Matrix4f projection, org.joml.Matrix4f culling, com.mojang.blaze3d.buffers.GpuBufferSlice fog, org.joml.Vector4f fogColor, boolean worldFog, CallbackInfo ci) {
        Carbon.RENDERER.viewRotation.set(view);
        Carbon.RENDERER.projection.set(projection);
        // Fabric 版 Iris 在主通道提取实体之前渲染光影阴影，帧状态需在此提前初始化；视锥按原版 prepareCullFrustum 构造
        Frustum frustum = new Frustum(view, culling);
        frustum.prepare(camera.position().x, camera.position().y, camera.position().z);
        Carbon.RENDERER.beginFrame(camera, frustum, delta.getGameTimeDeltaPartialTick(false));
    }

    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
    private Iterable<Entity> imo$entities(ClientLevel level, Operation<Iterable<Entity>> original) {
        return Carbon.RENDERER.collect(original.call(level));
    }
}
