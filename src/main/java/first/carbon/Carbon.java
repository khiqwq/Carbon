package first.carbon;

import com.mojang.brigadier.arguments.BoolArgumentType;
import first.carbon.client.ItemBatchRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class Carbon implements ClientModInitializer {
    public static final first.carbon.compat.IrisCompatibility IRIS = new first.carbon.compat.IrisCompatibility();
    public static final first.carbon.compat.SodiumCompatibility SODIUM = new first.carbon.compat.SodiumCompatibility();
    public static final first.carbon.compat.DroppedItemCompatibility DROPPED = new first.carbon.compat.DroppedItemCompatibility();
    public static final first.carbon.client.GenericItemRenderer GENERIC = new first.carbon.client.GenericItemRenderer();
    public static final first.carbon.client.GenericShadowRenderer SHADOWS = new first.carbon.client.GenericShadowRenderer();
    public static final ItemBatchRenderer RENDERER = new ItemBatchRenderer();
    public static final Settings CONFIG = new Settings();
    private static Boolean openGl;

    // 当前渲染后端是否为 OpenGL；26.1.2 只有 OpenGL 后端，保留该判断供光影联动提前返回
    public static boolean openGl() {
        Boolean value = openGl;
        if (value == null) openGl = value = "OpenGL".equals(com.mojang.blaze3d.systems.RenderSystem.getDevice().getBackendName());
        return value;
    }

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
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(Identifier.fromNamespaceAndPath("carbon", "meshes"),
                (ResourceManagerReloadListener) manager -> invalidate());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> invalidate());
        // 对应 NeoForge 的 AfterOpaqueFeatures：不透明特性批次执行之后
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(context -> RENDERER.flush(context.levelState().cameraRenderState));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> invalidate());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                literal("carbon")
                        .then(literal("config").executes(context -> {
                            // 聊天框在命令返回后才关闭，排到下一轮任务再打开。
                            var minecraft = net.minecraft.client.Minecraft.getInstance();
                            minecraft.schedule(() -> minecraft.setScreen(new first.carbon.client.ConfigScreen(null)));
                            return 1;
                        }))
                        .then(literal("enabled").then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
                            CONFIG.enabled.set(BoolArgumentType.getBool(context, "enabled"));
                            CONFIG.save();
                            return 1;
                        })))
                        .then(literal("stats").executes(context -> {
                            context.getSource().sendFeedback(Component.literal(RENDERER.statistics()));
                            return 1;
                        }))));
    }

    // Fabric 的断开事件可能在网络线程触发，GL 资源统一回到渲染线程释放
    private static void invalidate() {
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            RENDERER.invalidate();
            GENERIC.invalidate();
            SHADOWS.invalidate();
        } else net.minecraft.client.Minecraft.getInstance().execute(Carbon::invalidate);
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
