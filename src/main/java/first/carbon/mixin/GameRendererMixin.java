package first.carbon.mixin;

import first.carbon.Carbon;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 替代 NeoForge 的 RenderFrameEvent.Pre。
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void carbon$frame(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        Carbon.beginFrame();
    }
}
