package first.carbon.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import first.carbon.Carbon;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.3 的实心地形与实心特性共用一个原版 RenderPass，掉落物与阴影在其末尾画进同一个通道与渲染目标。
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
    private void carbon$prepare(CallbackInfo ci) {
        Carbon.RENDERER.prepare(levelRenderState.cameraRenderState);
    }

    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void carbon$solid(ChunkSectionsToRender chunks, FeatureRenderDispatcher.PreparedFrame frame, RenderPass renderPass, CallbackInfo ci) {
        Carbon.RENDERER.flush(renderPass, levelRenderState.cameraRenderState);
    }
}
