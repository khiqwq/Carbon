package first.carbon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// 原版只请求 OpenGL 3.2 核心上下文；按 NeoForge 的做法依次尝试 4.6 至 3.3，失败时回到原版 3.2。
@Mixin(Window.class)
public abstract class WindowMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
    private long carbon$context(int width, int height, CharSequence title, long monitor, long share, Operation<Long> original) {
        long window = 0;
        if (!Boolean.getBoolean("carbon.vanillaContext")) {
            GLFWErrorCallback previous = GLFW.glfwSetErrorCallback(null);
            try {
                for (int version : new int[]{46, 45, 44, 43, 42, 41, 40, 33}) {
                    if (window == 0) {
                        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, version / 10);
                        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, version % 10);
                        window = original.call(width, height, title, monitor, share);
                    }
                }
            } finally {
                GLFW.glfwSetErrorCallback(previous);
            }
        }
        if (window == 0) {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            window = original.call(width, height, title, monitor, share);
        }
        return window;
    }
}
