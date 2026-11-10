package first.carbon.mixin;

import first.carbon.client.B3d;
import net.minecraft.client.renderer.DynamicUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Carbon 的每次绘制 uniform 与原版动态 uniform 同步按帧轮换。
@Mixin(DynamicUniforms.class)
public abstract class DynamicUniformsMixin {
    @Inject(method = "reset", at = @At("TAIL"))
    private void carbon$reset(CallbackInfo ci) {
        B3d.endFrame();
    }
}
