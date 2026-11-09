package first.carbon;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Carbon implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("Carbon");
    public static final Settings CONFIG = new Settings();

    @Override
    public void onInitializeClient() {
        first.carbon.client.ClientEvents.register();
        CONFIG.load();
    }

    public static final class Client {
        public static final first.carbon.compat.IrisCompatibility IRIS = new first.carbon.compat.IrisCompatibility();
        public static final first.carbon.client.ItemBatchRenderer RENDERER = new first.carbon.client.ItemBatchRenderer();
        public static final first.carbon.client.DroppedItemBatchRenderer DROPPED_RENDERER = new first.carbon.client.DroppedItemBatchRenderer();
    }

    // 客户端配置：读写 config/carbon-client.toml，保持与旧版配置文件的键名一致。
    public static final class Settings {
        private final Path path = FabricLoader.getInstance().getConfigDir().resolve("carbon-client.toml");
        public final Switch enabled = new Switch("enabled", true);
        public final Switch optimizeDroppedItems = new Switch("optimizeDroppedItems", true);
        public final Switch physicalDroppedItems = new Switch("physicalDroppedItems", false);
        public final Switch skipVisibilityChecks = new Switch("skipVisibilityChecks", true);
        public final Number cacheUpdateBudgetMs = new Number("cacheUpdateBudgetMs", 2.0, 0.0, 20.0);
        private final List<Option> options = List.of(enabled, optimizeDroppedItems, physicalDroppedItems, skipVisibilityChecks, cacheUpdateBudgetMs);

        private Settings() {
        }

        public void load() {
            if (Files.isRegularFile(path)) {
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        String text = line.trim();
                        int separator = text.indexOf('=');
                        if (text.isEmpty() || text.startsWith("#") || text.startsWith("[") || separator < 0) continue;
                        String key = text.substring(0, separator).trim();
                        String value = text.substring(separator + 1).trim();
                        for (Option option : options) {
                            if (option.key.equals(key)) option.accept(value);
                        }
                    }
                } catch (IOException | RuntimeException exception) {
                    LOGGER.warn("Unable to read client configuration", exception);
                }
            } else {
                save();
            }
        }

        public void save() {
            StringBuilder text = new StringBuilder("# Carbon client configuration\n");
            for (Option option : options) text.append(option.key).append(" = ").append(option.value()).append('\n');
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                LOGGER.warn("Unable to save client configuration", exception);
            }
        }
    }

    public abstract static class Option {
        final String key;

        private Option(String key) {
            this.key = key;
        }

        abstract void accept(String value);

        abstract String value();
    }

    public static final class Switch extends Option {
        private boolean value;

        private Switch(String key, boolean value) {
            super(key);
            this.value = value;
        }

        public boolean get() {
            return value;
        }

        public void set(boolean value) {
            this.value = value;
        }

        @Override
        void accept(String value) {
            this.value = Boolean.parseBoolean(value);
        }

        @Override
        String value() {
            return Boolean.toString(value);
        }
    }

    public static final class Number extends Option {
        private final double minimum, maximum;
        private double value;

        private Number(String key, double value, double minimum, double maximum) {
            super(key);
            this.value = value;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        public double get() {
            return value;
        }

        public void set(double value) {
            this.value = Math.max(minimum, Math.min(maximum, value));
        }

        @Override
        void accept(String value) {
            set(Double.parseDouble(value));
        }

        @Override
        String value() {
            return Double.toString(this.value);
        }
    }
}
