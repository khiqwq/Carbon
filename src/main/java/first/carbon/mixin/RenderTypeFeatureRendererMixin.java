package first.carbon.mixin;

import first.carbon.Carbon;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// 原版分组的绘制完成后，在同一阶段与渲染目标中补上实例化的物品与物品阴影。
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMixin {
    @Inject(method = "executeGroup", at = @At("TAIL"))
    private void carbon$execute(FeatureFrameContext context, int groupIndex, List<?> submits, boolean strictlyOrdered, CallbackInfo ci) {
        Object self = this;
        if (self instanceof ItemFeatureRenderer) Carbon.GENERIC.execute(groupIndex);
        else if (self instanceof ShadowFeatureRenderer) Carbon.SHADOWS.flush();
    }
}
