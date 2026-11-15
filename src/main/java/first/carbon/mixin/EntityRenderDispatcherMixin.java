package first.carbon.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import first.carbon.Carbon;
import first.carbon.client.DroppedItemBatchRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin implements DroppedItemBatchRenderer.ShadowSettings {
    @Shadow
    private boolean renderHitBoxes;
    @Shadow
    private boolean shouldRenderShadow;

    @Override
    public boolean imo$shadows() {
        return shouldRenderShadow;
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void imo$direct(Entity entity, double x, double y, double z, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (entity instanceof ItemEntity item && !Carbon.IRIS.shadowPass() && !renderHitBoxes && !item.hasCustomName() && !item.shouldShowName()
                && !item.displayFireAnimation() && !item.isCurrentlyGlowing()
                && ((EntityRenderDispatcher) (Object) this).getRenderer(item).getClass() == ItemEntityRenderer.class) {
            var renderer = Carbon.DROPPED_RENDERER;
            if (renderer.submitEntity(item, partialTick, light, pose, buffers)) {
                renderer.addShadow(item, partialTick, shouldRenderShadow && Minecraft.getInstance().options.entityShadows().get());
                ci.cancel();
            }
        }
    }

    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
    private static void imo$shadow(PoseStack pose, MultiBufferSource buffers, EntityRenderState state, float strength, float partialTick, LevelReader level, float radius, CallbackInfo ci) {
        if (radius == 0.15f && state instanceof ItemEntityRenderState item
                && Carbon.RENDERER.submitShadow(Carbon.DROPPED_RENDERER.entity(item), partialTick, strength, buffers)) {
            ci.cancel();
        }
    }
}
