package first.carbon.mixin;

import first.carbon.Carbon;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @WrapOperation(method = "collectVisibleEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
    private Iterable<Entity> imo$entities(ClientLevel level, Operation<Iterable<Entity>> original) {
        Iterable<Entity> entities = original.call(level);
        return Carbon.CONFIG.optimizeDroppedItems.get() ? Carbon.DROPPED_RENDERER.collect(entities) : entities;
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void imo$shadows(GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline, Camera camera, GameRenderer renderer, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        Carbon.RENDERER.beginWorld(camera, view, projection);
        Carbon.DROPPED_RENDERER.beginFrame(camera, view, projection, delta.getGameTimeDeltaPartialTick(false));
    }
}
