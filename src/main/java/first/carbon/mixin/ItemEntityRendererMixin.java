package first.carbon.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import first.carbon.Carbon;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin extends EntityRenderer<ItemEntity, ItemEntityRenderState> {

    protected ItemEntityRendererMixin(EntityRendererProvider.Context context) {
        super(context);
    }

    // 渲染状态不含实体，提取阶段记录以便渲染阶段接管。
    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
            at = @At("HEAD")
    )
    private void imo$extract(ItemEntity entity, ItemEntityRenderState state, float partialTick, CallbackInfo ci) {
        Carbon.DROPPED_RENDERER.captureEntity(entity, state, partialTick);
    }

    @Inject(
            method = "render(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void imo$render(ItemEntityRenderState state, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        ItemEntity entity = Carbon.DROPPED_RENDERER.entity(state);
        if (entity != null && Carbon.DROPPED_RENDERER.submitEntity(entity, Carbon.DROPPED_RENDERER.entityPartialTick(), light, pose, buffers)) {
            super.render(state, pose, buffers, light);
            ci.cancel();
        }
    }
}
