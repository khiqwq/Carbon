package first.carbon.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import first.carbon.client.ItemBatchRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 渲染状态不携带物品，渲染模型列表前按状态还原当前物品。
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements ItemBatchRenderer.StackHolder {
    @Unique
    private ItemStack imo$stack;

    @Override
    public void imo$stack(ItemStack stack) {
        imo$stack = stack;
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"))
    private void imo$push(PoseStack pose, MultiBufferSource buffers, int light, int overlay, CallbackInfo ci) {
        ItemBatchRenderer.pushStack(imo$stack);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("RETURN"))
    private void imo$pop(PoseStack pose, MultiBufferSource buffers, int light, int overlay, CallbackInfo ci) {
        ItemBatchRenderer.popStack();
    }
}
