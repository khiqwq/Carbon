package first.carbon.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 其他模组在原版 ItemEntityRenderer 中改写掉落物（静止、平放、精确模型、隐藏）时，掉落物专用管线让位：
// 掉落物交还原版渲染器以保留这些效果，生成的标准物品提交仍由通用管线实例化。只读取对方自己的开关，关闭后自动恢复。
public final class DroppedItemCompatibility {
    private final Logger logger = LoggerFactory.getLogger("Carbon");
    private final List<Check> checks = new ArrayList<>();
    private boolean initialized;
    private String last;

    public boolean overridden() {
        if (!initialized) {
            initialized = true;
            initialize();
        }
        String active = null;
        for (Check check : checks) {
            if (!check.failed) {
                try {
                    if (check.test.get()) {
                        active = check.name;
                        break;
                    }
                } catch (Throwable exception) {
                    check.failed = true;
                    logger.warn("Unable to read {}; ignoring it for dropped items", check.name, exception);
                }
            }
        }
        if (!Objects.equals(active, last)) {
            if (active != null) logger.info("Leaving dropped items to the vanilla renderer while {} is enabled", active);
            else logger.info("Dedicated dropped-item pipeline restored");
            last = active;
        }
        return active != null;
    }

    private void initialize() {
        var loader = FabricLoader.getInstance();
        if (loader.isModLoaded("asa")) {
            option("antideath-survival-assistant disableItemEntityMulPose", "io.github.jfglzs.asa.config.Configs$Disables", "DISABLE_ITEM_ENTITY_MULPOSE");
        }
        if (loader.isModLoaded("tweakermore")) {
            option("TweakerMore preciseItemEntityModel", "me.fallenbreath.tweakermore.config.TweakerMoreConfigs", "PRECISE_ITEM_ENTITY_MODEL");
        }
        if (loader.isModLoaded("meteor-client")) meteor();
    }

    // malilib 配置项（ConfigBoolean、ConfigBooleanHotkeyed 等）统一提供 getBooleanValue()
    private void option(String name, String owner, String field) {
        try {
            Object option = Class.forName(owner).getField(field).get(null);
            Method value = option.getClass().getMethod("getBooleanValue");
            checks.add(new Check(name, () -> (boolean) value.invoke(option)));
        } catch (ReflectiveOperationException | LinkageError exception) {
            logger.debug("{} not found: {}", name, exception.toString());
        }
    }

    private void meteor() {
        try {
            Class<?> modules = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
            Method instance = modules.getMethod("get");
            Method isActive = modules.getMethod("isActive", Class.class);
            Method module = modules.getMethod("get", Class.class);
            Class<?> physics = Class.forName("meteordevelopment.meteorclient.systems.modules.render.ItemPhysics");
            checks.add(new Check("Meteor Client item-physics", () -> {
                Object registry = instance.invoke(null);
                return registry != null && (boolean) isActive.invoke(registry, physics);
            }));
            Class<?> noRender = Class.forName("meteordevelopment.meteorclient.systems.modules.render.NoRender");
            Method noEntity = noRender.getMethod("noEntity", EntityType.class);
            checks.add(new Check("Meteor Client no-render (item entities)", () -> {
                Object registry = instance.invoke(null);
                return registry != null && (boolean) isActive.invoke(registry, noRender)
                        && (boolean) noEntity.invoke(module.invoke(registry, noRender), net.minecraft.world.entity.EntityTypes.ITEM);
            }));
        } catch (ReflectiveOperationException | LinkageError exception) {
            logger.debug("Meteor Client modules not found: {}", exception.toString());
        }
    }

    @FunctionalInterface
    private interface Test {
        boolean get() throws Throwable;
    }

    private static final class Check {
        final String name;
        final Test test;
        boolean failed;

        Check(String name, Test test) {
            this.name = name;
            this.test = test;
        }
    }
}
