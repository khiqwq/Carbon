package first.carbon.mixin;

import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
    @Accessor("textureTransform")
    TextureTransform carbon$textureTransform();

    @Accessor("layeringTransform")
    LayeringTransform carbon$layeringTransform();

    @Accessor("useLightmap")
    boolean carbon$useLightmap();

    @Accessor("useOverlay")
    boolean carbon$useOverlay();
}
