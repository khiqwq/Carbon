package first.carbon.mixin;

import first.carbon.Carbon;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

// 26.2 的物品特性先按分组生成顶点：可实例化的提交在此移出，由 RenderTypeFeatureRendererMixin 在执行该分组时绘制。
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {
    @ModifyVariable(method = "buildGroup", at = @At("HEAD"), argsOnly = true)
    private List<ItemFeatureRenderer.Submit> carbon$submits(List<ItemFeatureRenderer.Submit> submits, FeatureFrameContext context) {
        return Carbon.GENERIC.collect(submits);
    }
}
