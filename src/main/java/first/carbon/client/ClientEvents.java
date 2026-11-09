package first.carbon.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import first.carbon.Carbon;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientEvents {

    public static void register() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path oldConfig = configDir.resolve("item_model_optimization-client.toml");
        Path config = configDir.resolve("carbon-client.toml");
        if (Files.isRegularFile(oldConfig) && !Files.exists(config)) {
            try { Files.copy(oldConfig, config); }
            catch (IOException exception) { LogManager.getLogger("Carbon").warn("Unable to migrate configuration", exception); }
        }
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return new ResourceLocation("carbon", "cache");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                invalidate();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> invalidate());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> invalidate());
        WorldRenderEvents.START.register(context -> {
            Carbon.Client.IRIS.beginFrame();
            Carbon.Client.RENDERER.beginFrame();
            Carbon.Client.RENDERER.beginWorld(context.camera(), context.matrixStack().last().pose(), context.projectionMatrix());
            Carbon.Client.DROPPED_RENDERER.beginFrame(context.camera(), context.matrixStack().last().pose(), context.projectionMatrix(), context.tickDelta());
        });
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            Carbon.Client.DROPPED_RENDERER.flush();
            Carbon.Client.RENDERER.flushShadows();
        });
        WorldRenderEvents.END.register(context -> {
            Carbon.Client.DROPPED_RENDERER.flush();
            Carbon.Client.DROPPED_RENDERER.endFrame();
            Carbon.Client.RENDERER.endWorld();
        });
        // 1.17 fabric command api v1：向公共静态 DISPATCHER 注册。
        ClientCommandManager.DISPATCHER.register(
                ClientCommandManager.literal("carbon")
                        .then(ClientCommandManager.literal("enabled").then(ClientCommandManager.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                            Carbon.CONFIG.enabled.set(BoolArgumentType.getBool(context, "enabled"));
                            Carbon.CONFIG.save();
                            context.getSource().sendFeedback(new net.minecraft.network.chat.TextComponent("Carbon enabled = " + Carbon.CONFIG.enabled.get()));
                            return 1;
                        })))
                        .then(ClientCommandManager.literal("config").executes(context -> {
                            // 聊天框在命令返回后才关闭，推迟到下一次任务循环打开。
                            Minecraft.getInstance().tell(() -> Minecraft.getInstance().setScreen(new ItemConfigScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("stats").executes(context -> {
                            context.getSource().sendFeedback(new net.minecraft.network.chat.TextComponent(Carbon.Client.RENDERER.statistics() + "; dropped: " + Carbon.Client.DROPPED_RENDERER.statistics()));
                            return 1;
                        })));
    }

    // 断开事件可能在网络线程触发，GL 资源统一回到渲染线程释放。
    private static void invalidate() {
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            Carbon.Client.IRIS.invalidate();
            Carbon.Client.RENDERER.invalidate();
            Carbon.Client.DROPPED_RENDERER.invalidate();
        } else Minecraft.getInstance().execute(ClientEvents::invalidate);
    }
}
