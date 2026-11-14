package first.carbon.mixin;

import first.carbon.Carbon;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.1.2 的一次完整特性渲染为不透明、半透明、粒子三段顺序执行：整段前后计入特性帧深度，Iris 阴影帧作为嵌套层。
@Mixin(FeatureRenderDispatcher.class)
public abstract class FeatureFrameMixin {
    @Inject(method = "renderAllFeatures", at = @At("HEAD"))
    private void carbon$begin(CallbackInfo ci) {
        Carbon.GENERIC.beginFrame();
        Carbon.SHADOWS.beginFrame();
    }

    @Inject(method = "renderSolidFeatures", at = @At("HEAD"))
    private void carbon$solid(CallbackInfo ci) {
        Carbon.GENERIC.beforeSolid();
    }

    @Inject(method = "renderAllFeatures", at = @At("RETURN"))
    private void carbon$end(CallbackInfo ci) {
        Carbon.GENERIC.endFrame();
        Carbon.SHADOWS.endFrame();
    }
}
