package first.carbon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import first.carbon.Carbon;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// 1.21.11 的物品特性统一写入 BufferSource；可实例化的提交在此截取，方法末尾直接绘制。
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void carbon$begin(CallbackInfo ci) { Carbon.GENERIC.begin(); }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;getItemSubmits()Ljava/util/List;"))
    private List<SubmitNodeStorage.ItemSubmit> carbon$submits(SubmitNodeCollection nodes, Operation<List<SubmitNodeStorage.ItemSubmit>> original) {
        return Carbon.GENERIC.collect(original.call(nodes));
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void carbon$flush(CallbackInfo ci) { Carbon.GENERIC.flush(); }
}
