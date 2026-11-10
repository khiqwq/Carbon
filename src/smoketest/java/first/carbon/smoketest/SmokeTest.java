package first.carbon.smoketest;

import first.carbon.Carbon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 自动化冒烟测试：建平坦世界、生成掉落物、记录 Carbon 统计并截图后退出。
public final class SmokeTest implements ClientModInitializer {
    private final Logger logger = LoggerFactory.getLogger("CarbonSmokeTest");
    private int stage;
    private int ticks;
    private final int stress = Integer.getInteger("carbon.smoke.stress", 0);
    private final boolean compat = Boolean.getBoolean("carbon.smoke.compat");
    private int compatIndex;
    private static final String[][] COMPAT = {
            {"asa", "io.github.jfglzs.asa.config.Configs$Disables", "DISABLE_ITEM_ENTITY_MULPOSE"},
            {"tweakermore", "me.fallenbreath.tweakermore.config.TweakerMoreConfigs", "PRECISE_ITEM_ENTITY_MODEL"},
            {"meteor-client", "meteordevelopment.meteorclient.systems.modules.render.ItemPhysics", null},
    };

    private void setCompat(String[] target, boolean value) {
        try {
            if (target[2] != null) {
                Object option = Class.forName(target[1]).getField(target[2]).get(null);
                option.getClass().getMethod("setBooleanValue", boolean.class).invoke(option, value);
            } else {
                Class<?> modules = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
                Object module = modules.getMethod("get", Class.class).invoke(modules.getMethod("get").invoke(null), Class.forName(target[1]));
                if ((boolean) module.getClass().getMethod("isActive").invoke(module) != value) module.getClass().getMethod("toggle").invoke(module);
            }
            logger.info("[compat] {} -> {}", target[0], value);
        } catch (ReflectiveOperationException exception) {
            logger.error("[compat] unable to set {}", target[0], exception);
        }
    }

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("carbon.bench") && System.getProperty("carbon.scale") == null) ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft minecraft) {
        ticks++;
        if (stage == 0 && ticks > 40 && minecraft.getOverlay() == null && minecraft.level == null && minecraft.screen != null) {
            stage = 1;
            String name = "carbon-smoke-" + System.currentTimeMillis();
            GameRules rules = new GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
            rules.set(GameRules.ADVANCE_TIME, false, null);
            rules.set(GameRules.ADVANCE_WEATHER, false, null);
            rules.set(GameRules.SPAWN_MOBS, false, null);
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(1L, false, false),
                    registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), new net.minecraft.client.gui.screens.TitleScreen());
        }
        if (stage == 1 && minecraft.player != null && minecraft.level != null && minecraft.getSingleplayerServer() != null && minecraft.screen == null) {
            stage = 2;
            ticks = 0;
            var player = minecraft.player;
            var server = minecraft.getSingleplayerServer();
            double x = player.getX(), y = player.getY(), z = player.getZ();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                level.setDayTime(6000);
                var serverPlayer = server.getPlayerList().getPlayers().getFirst();
                serverPlayer.getAbilities().flying = true;
                serverPlayer.teleportTo(x, y + 3, z);
                serverPlayer.setYRot(0);
                serverPlayer.setXRot(45);
                ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
                ItemStack[] stacks = {new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.STONE, 32), new ItemStack(Items.GLASS),
                        book, new ItemStack(Items.APPLE, 64), new ItemStack(Items.OAK_LOG, 3), new ItemStack(Items.COMPASS), new ItemStack(Items.WHITE_STAINED_GLASS)};
                if (Boolean.getBoolean("carbon.smoke.noTranslucent")) {
                    stacks[3] = new ItemStack(Items.BREAD, 5);
                    stacks[6] = new ItemStack(Items.GOLD_INGOT);
                    stacks[7] = new ItemStack(Items.IRON_INGOT, 20);
                }
                for (int slot = 0; slot < 9; slot++) serverPlayer.getInventory().setItem(slot, stacks[slot % stacks.length].copy());
                serverPlayer.getInventory().setSelectedSlot(0);
                for (int i = 0; i < 400; i++) {
                    ItemEntity item = new ItemEntity(level, x - 10 + (i % 20), y + 2, z + 3 + (i / 20), stacks[i % stacks.length].copy(), 0, 0, 0);
                    item.setPickUpDelay(32767);
                    level.addFreshEntity(item);
                }
            });
        }
        if (stage == 2 && ticks == 160) {
            report(minecraft, "default");
            Carbon.CONFIG.physicalDroppedItems.set(true);
            stage = compat ? 20 : stress > 0 ? 10 : 3;
            ticks = 0;
        }
        // 兼容模式：依次开启其他模组改写原版掉落物的选项，确认专用管线让位并截图
        if (stage == 20) {
            if (ticks == 1) {
                while (compatIndex < COMPAT.length && !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(COMPAT[compatIndex][0])) compatIndex++;
                if (compatIndex == COMPAT.length) {
                    stage = stress > 0 ? 10 : 3;
                    ticks = 0;
                } else setCompat(COMPAT[compatIndex], true);
            }
            if (ticks == 80) {
                report(minecraft, "compat-" + COMPAT[compatIndex][0]);
                setCompat(COMPAT[compatIndex], false);
            }
            if (ticks == 120) {
                logger.info("[compat-{}-off] carbon: {}", COMPAT[compatIndex][0], Carbon.RENDERER.statistics());
                compatIndex++;
                ticks = 0;
            }
        }
        // 压力模式：每 1.5 秒轮换物理化、总开关与掉落物管线，持续指定秒数后回到常规流程
        if (stage == 10) {
            if (ticks % 30 == 0) {
                int step = ticks / 30;
                Carbon.CONFIG.physicalDroppedItems.set(step % 2 == 0);
                Carbon.CONFIG.enabled.set(step % 5 != 4);
                Carbon.CONFIG.optimizeDroppedItems.set(step % 7 != 6);
            }
            if (ticks >= stress * 20) {
                Carbon.CONFIG.enabled.set(true);
                Carbon.CONFIG.optimizeDroppedItems.set(true);
                Carbon.CONFIG.physicalDroppedItems.set(true);
                logger.info("[stress] survived {} s", stress);
                stage = 3;
                ticks = 0;
            }
        }
        if (stage == 3 && ticks == 120) {
            report(minecraft, "physics");
            Carbon.CONFIG.physicalDroppedItems.set(false);
            Carbon.CONFIG.enabled.set(false);
            stage = 4;
            ticks = 0;
        }
        if (stage == 4 && ticks == 60) {
            report(minecraft, "disabled");
            Carbon.CONFIG.enabled.set(true);
            stage = 5;
            ticks = 0;
        }
        if (stage == 5 && ticks == 60) {
            report(minecraft, "reenabled");
            stage = 6;
            minecraft.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Carbon smoke test finished"));
        }
        if (stage == 6 && minecraft.level == null && minecraft.getSingleplayerServer() == null) {
            stage = 7;
            minecraft.stop();
        }
    }

    private void report(Minecraft minecraft, String name) {
        logger.info("[{}] fps={} entities={} carbon: {}", name, minecraft.getFps(), minecraft.level.getEntityCount(), Carbon.RENDERER.statistics());
        logger.info("GL33={} GL43={} version={} renderer={}", org.lwjgl.opengl.GL.getCapabilities().OpenGL33, org.lwjgl.opengl.GL.getCapabilities().OpenGL43,
                org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION), org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
        for (Object target : new Object[]{Carbon.RENDERER, Carbon.GENERIC, Carbon.SHADOWS, Carbon.IRIS}) {
            StringBuilder state = new StringBuilder(target.getClass().getSimpleName()).append(':');
            for (var field : target.getClass().getDeclaredFields()) {
                if (field.getType().isPrimitive() && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    try {
                        field.setAccessible(true);
                        state.append(' ').append(field.getName()).append('=').append(field.get(target));
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
            logger.info(state.toString());
        }
        Screenshot.grab(minecraft.gameDirectory, "carbon-" + name + ".png", minecraft.getMainRenderTarget(), 1, message -> logger.info(message.getString()));
    }
}
