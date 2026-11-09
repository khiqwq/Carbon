package first.carbon;

import com.mojang.brigadier.arguments.BoolArgumentType;
import first.carbon.client.ItemBatchRenderer;
import first.carbon.client.DroppedItemBatchRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class Carbon implements ClientModInitializer {
    public static final first.carbon.compat.IrisCompatibility IRIS = new first.carbon.compat.IrisCompatibility();
    public static final ItemBatchRenderer RENDERER = new ItemBatchRenderer();
    public static final DroppedItemBatchRenderer DROPPED_RENDERER = new DroppedItemBatchRenderer();
    public static final Settings CONFIG = new Settings();

    @Override
    public void onInitializeClient() {
        Path oldConfig = FabricLoader.getInstance().getConfigDir().resolve("item_model_optimization-client.toml");
        if (Files.isRegularFile(oldConfig) && !Files.exists(CONFIG.path)) {
            try {
                Files.copy(oldConfig, CONFIG.path);
            } catch (IOException exception) {
                LoggerFactory.getLogger("Carbon").warn("Unable to migrate the previous client configuration", exception);
            }
        }
        CONFIG.load();
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath("carbon", "caches");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                IRIS.invalidate();
                RENDERER.invalidate();
                DROPPED_RENDERER.invalidate();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            RENDERER.invalidate();
            DROPPED_RENDERER.invalidate();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            RENDERER.invalidate();
            DROPPED_RENDERER.invalidate();
        });
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            DROPPED_RENDERER.flush();
            RENDERER.flushShadows();
        });
        WorldRenderEvents.END.register(context -> {
            DROPPED_RENDERER.flush();
            DROPPED_RENDERER.endFrame();
            RENDERER.endWorld();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                literal("carbon")
                        .then(literal("config").executes(context -> {
                            // 聊天框在命令返回后才关闭，排到下一轮任务再打开。
                            var minecraft = net.minecraft.client.Minecraft.getInstance();
                            minecraft.tell(() -> minecraft.setScreen(new first.carbon.client.ConfigScreen(null)));
                            return 1;
                        }))
                        .then(literal("enabled").then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
                            CONFIG.enabled.set(BoolArgumentType.getBool(context, "enabled"));
                            CONFIG.save();
                            return 1;
                        })))
                        .then(literal("stats").executes(context -> {
                            context.getSource().sendFeedback(Component.literal(RENDERER.statistics() + "; dropped: " + DROPPED_RENDERER.statistics()));
                            return 1;
                        }))));
    }

    // 帧开始时由 GameRendererMixin 调用，对应 NeoForge 的 RenderFrameEvent.Pre。
    public static void beginFrame() {
        IRIS.beginFrame();
        RENDERER.beginFrame();
    }

    // 与 NeoForge 版本同名同格式的 config/carbon-client.toml。
    public static final class Settings {
        public final Path path = FabricLoader.getInstance().getConfigDir().resolve("carbon-client.toml");
        public final Value<Boolean> enabled = new Value<>("enabled", "Cache standard item model geometry and instance matching render submissions.", true, Boolean::parseBoolean);
        public final Value<Boolean> optimizeDroppedItems = new Value<>("optimizeDroppedItems", "Take over dropped-item models, spin and scale on the GPU. Disable to preserve other mods' dropped-item rendering and transforms.", true, Boolean::parseBoolean);
        public final Value<Boolean> physicalDroppedItems = new Value<>("physicalDroppedItems", "Visual-only GPU tumbling and resting dropped items; requires optimizeDroppedItems. Does not change collisions, velocity or pickup.", false, Boolean::parseBoolean);
        public final Value<Boolean> skipVisibilityChecks = new Value<>("skipVisibilityChecks", "Skip frustum and Sodium occlusion checks for the dedicated dropped-item pipeline; distance limits still apply.", true, Boolean::parseBoolean);
        public final Value<Double> cacheUpdateBudgetMs = new Value<>("cacheUpdateBudgetMs", "CPU cache update budget per frame in milliseconds. Keep drawing the previous scene until the queue completes. 0 updates immediately; a single item can exceed the budget.\nRange: 0.0 ~ 20.0", 2.0, text -> Math.clamp(Double.parseDouble(text), 0.0, 20.0));
        public final List<Value<?>> values = List.of(enabled, optimizeDroppedItems, physicalDroppedItems, skipVisibilityChecks, cacheUpdateBudgetMs);

        private Settings() {
        }

        public void load() {
            if (Files.isRegularFile(path)) {
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        int split = line.indexOf('=');
                        if (!line.isBlank() && !line.strip().startsWith("#") && split > 0) {
                            String key = line.substring(0, split).strip();
                            String text = line.substring(split + 1).replaceAll("#.*", "").strip();
                            for (Value<?> value : values) {
                                if (value.key.equals(key)) {
                                    try {
                                        value.parse(text);
                                    } catch (RuntimeException exception) {
                                        LoggerFactory.getLogger("Carbon").warn("Invalid {} entry '{}', using the default", key, text);
                                    }
                                }
                            }
                        }
                    }
                } catch (IOException exception) {
                    LoggerFactory.getLogger("Carbon").warn("Unable to read {}", path, exception);
                }
            }
            save();
        }

        public void save() {
            StringBuilder text = new StringBuilder();
            for (Value<?> value : values) {
                for (String comment : value.comment.split("\n")) text.append("#").append(comment).append('\n');
                text.append(value.key).append(" = ").append(value.get()).append("\n\n");
            }
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, text.toString().stripTrailing() + "\n", StandardCharsets.UTF_8);
            } catch (IOException exception) {
                LoggerFactory.getLogger("Carbon").warn("Unable to save {}", path, exception);
            }
        }
    }

    public static final class Value<T> {
        public final String key;
        public final String comment;
        public final T defaultValue;
        private final Function<String, T> parser;
        private volatile T value;

        private Value(String key, String comment, T defaultValue, Function<String, T> parser) {
            this.key = key;
            this.comment = comment;
            this.defaultValue = defaultValue;
            this.parser = parser;
            value = defaultValue;
        }

        public T get() {
            return value;
        }

        public void set(T value) {
            this.value = value;
        }

        public String translation() {
            return "carbon.configuration." + key;
        }

        private void parse(String text) {
            String clean = text.toLowerCase(Locale.ROOT);
            if (defaultValue instanceof Boolean && !clean.equals("true") && !clean.equals("false")) throw new IllegalArgumentException(key + " = " + text);
            value = parser.apply(clean);
        }
    }
}
