package first.carbon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import first.carbon.Carbon;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Mixin(ShadowFeatureRenderer.class)
public abstract class ShadowFeatureRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;getShadowSubmits()Ljava/util/List;"))
    private List<SubmitNodeStorage.ShadowSubmit> carbon$shadows(SubmitNodeCollection nodes, Operation<List<SubmitNodeStorage.ShadowSubmit>> original) {
        return Carbon.SHADOWS.collect(original.call(nodes));
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void carbon$flush(CallbackInfo ci) { Carbon.SHADOWS.flush(); }
}
