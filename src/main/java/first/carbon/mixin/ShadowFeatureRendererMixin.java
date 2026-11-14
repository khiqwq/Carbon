package first.carbon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import first.carbon.Carbon;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// 26.1.2 的阴影特性同样逐个提交直接绘制：移出标准物品圆形阴影，绘制完该批剩余提交后补上实例。
@Mixin(ShadowFeatureRenderer.class)
public abstract class ShadowFeatureRendererMixin {
    @WrapOperation(method = "renderTranslucent", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;getShadowSubmits()Ljava/util/List;"))
    private List<SubmitNodeStorage.ShadowSubmit> carbon$shadows(SubmitNodeCollection collection, Operation<List<SubmitNodeStorage.ShadowSubmit>> original) {
        return Carbon.SHADOWS.collect(original.call(collection));
    }

    @Inject(method = "renderTranslucent", at = @At("TAIL"))
    private void carbon$flush(SubmitNodeCollection collection, MultiBufferSource.BufferSource buffers, CallbackInfo ci) {
        Carbon.SHADOWS.flush();
    }
}
