package first.carbon.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import first.carbon.Carbon;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.BakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    @Redirect(method = {"getFoilBuffer", "getArmorFoilBuffer", "getCompassFoilBuffer"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private static VertexConsumer carbon$buffer(MultiBufferSource source, RenderType type) {
        return Carbon.IRIS.getBuffer(source, type);
    }

    // 采集模型几何时跳过附魔光效通道，光效由实例化通道单独处理。
    @ModifyVariable(method = "renderItem", at = @At("HEAD"), index = 8, argsOnly = true)
    private static ItemStackRenderState.FoilType imo$foil(ItemStackRenderState.FoilType foilType) {
        return Carbon.DROPPED_RENDERER.capturingModel() ? ItemStackRenderState.FoilType.NONE : foilType;
    }

    @Inject(method = "renderModelLists", at = @At("HEAD"), cancellable = true)
    private static void imo$submit(BakedModel model, int[] tintLayers, int light, int overlay, PoseStack pose, VertexConsumer vertices, CallbackInfo ci) {
        if (Carbon.RENDERER.submit(model, tintLayers, light, overlay, pose, vertices)) {
            ci.cancel();
        }
    }
}
