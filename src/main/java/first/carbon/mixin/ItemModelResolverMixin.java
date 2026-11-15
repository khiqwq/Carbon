package first.carbon.mixin;

import first.carbon.client.ItemBatchRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 记录渲染状态由哪个物品解析而来，供实例化时识别批次。
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {
    @Inject(method = "updateForTopItem", at = @At("HEAD"))
    private void imo$top(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, boolean leftHand,
                         Level level, LivingEntity entity, int seed, CallbackInfo ci) {
        ((ItemBatchRenderer.StackHolder) (Object) state).imo$stack(stack);
    }

    @Inject(method = "appendItemLayers", at = @At("HEAD"))
    private void imo$append(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, Level level,
                            LivingEntity entity, int seed, CallbackInfo ci) {
        ((ItemBatchRenderer.StackHolder) (Object) state).imo$stack(stack);
    }
}
