package first.carbon.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 26.1.2 没有公开 PreparedRenderType，Iris 绘制需要的渲染配置从 RenderType 内部读取。
@Mixin(RenderType.class)
public interface RenderTypeAccessor {
    @Accessor("state")
    RenderSetup carbon$setup();
}
