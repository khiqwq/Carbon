package first.carbon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import first.carbon.Carbon;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

// 记录世界雾参数，供独立实例化程序使用（26.1 由 CameraRenderState.fogData 提供）。
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @WrapOperation(method = "setupFog", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void carbon$fog(FogRenderer self, ByteBuffer buffer, int offset, Vector4f color, float start, float end, float renderStart, float renderEnd, float sky, float cloud, Operation<Void> original) {
        var renderer = Carbon.RENDERER;
        renderer.fogColor.set(color);
        renderer.fogStart = start;
        renderer.fogEnd = end;
        renderer.renderFogStart = renderStart;
        renderer.renderFogEnd = renderEnd;
        original.call(self, buffer, offset, color, start, end, renderStart, renderEnd, sky, cloud);
    }
}
