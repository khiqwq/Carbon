package first.carbon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// 原版 OpenGL 后端只请求 3.3 核心上下文，计算着色器与 Iris 实例化需要 4.3；按 NeoForge 的做法依次尝试 4.6 至 4.0，失败时回到原版 3.3。
// Vulkan 后端不受影响，Carbon 在非 OpenGL 后端下保持原版渲染。
@Mixin(Window.class)
public abstract class WindowMixin {
    @WrapOperation(method = "createGlfwWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static long carbon$context(int width, int height, CharSequence title, long monitor, long share, Operation<Long> original, @Local(argsOnly = true) GpuBackend backend) {
        if (!(backend instanceof GlBackend) || Boolean.getBoolean("carbon.vanillaContext")) return original.call(width, height, title, monitor, share);
        long window = 0;
        GLFWErrorCallback previous = GLFW.glfwSetErrorCallback(null);
        try {
            for (int version : new int[]{46, 45, 44, 43, 42, 41, 40}) {
                if (window == 0) {
                    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, version / 10);
                    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, version % 10);
                    window = original.call(width, height, title, monitor, share);
                }
            }
        } finally {
            GLFW.glfwSetErrorCallback(previous);
        }
        if (window == 0) {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            window = original.call(width, height, title, monitor, share);
        }
        return window;
    }
}
