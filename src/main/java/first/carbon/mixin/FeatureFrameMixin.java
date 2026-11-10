package first.carbon.mixin;

import first.carbon.Carbon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 跟踪已准备但未关闭的特性帧：主帧准备后、执行前，Iris 会完整渲染一次自己的阴影帧。
@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame")
public abstract class FeatureFrameMixin {
    @Inject(method = "begin", at = @At("RETURN"))
    private void carbon$begin(CallbackInfoReturnable<?> cir) {
        Carbon.GENERIC.beginFrame();
        Carbon.SHADOWS.beginFrame();
    }

    @Inject(method = "executeSolid", at = @At("HEAD"))
    private void carbon$solid(CallbackInfo ci) {
        Carbon.GENERIC.beforeSolid();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void carbon$close(CallbackInfo ci) {
        Carbon.GENERIC.endFrame();
        Carbon.SHADOWS.endFrame();
    }
}
