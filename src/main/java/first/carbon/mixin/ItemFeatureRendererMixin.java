package first.carbon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import first.carbon.Carbon;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// 26.1.2 的物品特性逐个提交直接绘制：遍历前移出可实例化的提交，该批剩余提交绘制完成后补上实例。
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {
    @WrapOperation(method = "renderSolid", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;getItemSubmits()Ljava/util/List;"))
    private List<SubmitNodeStorage.ItemSubmit> carbon$submits(SubmitNodeCollection collection, Operation<List<SubmitNodeStorage.ItemSubmit>> original) {
        return Carbon.GENERIC.collect(original.call(collection));
    }

    @Inject(method = "renderSolid", at = @At("TAIL"))
    private void carbon$flush(SubmitNodeCollection collection, MultiBufferSource.BufferSource buffers, OutlineBufferSource outline, CallbackInfo ci) {
        Carbon.GENERIC.execute();
    }
}
