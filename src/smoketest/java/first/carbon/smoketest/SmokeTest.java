package first.carbon.smoketest;

import first.carbon.Carbon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// 自动化冒烟测试：建平坦世界、生成掉落物、记录 Carbon 统计并截图后退出。
public final class SmokeTest implements ClientModInitializer {
    private final Logger logger = LogManager.getLogger("CarbonSmokeTest");
    private int stage;
    private int ticks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft minecraft) {
        ticks++;
        if (stage == 0 && ticks > 40 && minecraft.getOverlay() == null && minecraft.level == null && minecraft.screen != null) {
            stage = 1;
            String name = "carbon-smoke-" + System.currentTimeMillis();
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, DataPackConfig.DEFAULT);
            RegistryAccess.RegistryHolder registries = RegistryAccess.builtin();
            java.util.Properties properties = new java.util.Properties();
            properties.setProperty("level-seed", "1");
            properties.setProperty("level-type", "flat");
            properties.setProperty("generate-structures", "false");
            minecraft.createLevel(name, settings, registries, WorldGenSettings.create(registries, properties));
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
                var serverPlayer = server.getPlayerList().getPlayers().get(0);
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
                serverPlayer.getInventory().selected = 0;
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
            stage = 3;
            ticks = 0;
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
            minecraft.level.disconnect();
            minecraft.clearLevel();
            minecraft.stop();
        }
    }

    private void report(Minecraft minecraft, String name) {
        logger.info("[{}] fps={} entities={} carbon: {}; dropped: {}", name, minecraft.fpsString, minecraft.level.getEntityCount(),
                Carbon.Client.RENDERER.statistics(), Carbon.Client.DROPPED_RENDERER.statistics());
        logger.info("GL33={} GL43={} version={} renderer={}", org.lwjgl.opengl.GL.getCapabilities().OpenGL33, org.lwjgl.opengl.GL.getCapabilities().OpenGL43,
                org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION), org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
        for (Object target : new Object[]{Carbon.Client.DROPPED_RENDERER, Carbon.Client.RENDERER, Carbon.Client.IRIS}) {
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
        Screenshot.grab(minecraft.gameDirectory, "carbon-" + name + ".png", minecraft.getMainRenderTarget(), message -> logger.info(message.getString()));
    }
}
