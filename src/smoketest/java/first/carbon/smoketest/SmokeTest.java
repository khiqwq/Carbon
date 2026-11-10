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

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("carbon.bench")) ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft minecraft) {
        ticks++;
        if (stage == 0 && ticks > 40 && minecraft.gui.overlay() == null && minecraft.level == null && minecraft.gui.screen() != null) {
            stage = 1;
            String name = "carbon-smoke-" + System.currentTimeMillis();
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(1L, false, false),
                    registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), new net.minecraft.client.gui.screens.TitleScreen());
        }
        if (stage == 1 && minecraft.player != null && minecraft.level != null && minecraft.getSingleplayerServer() != null && minecraft.gui.screen() == null) {
            stage = 2;
            ticks = 0;
            var player = minecraft.player;
            var server = minecraft.getSingleplayerServer();
            double x = player.getX(), y = player.getY(), z = player.getZ();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                var rules = level.getGameRules();
                rules.set(GameRules.ADVANCE_TIME, false, server);
                rules.set(GameRules.ADVANCE_WEATHER, false, server);
                rules.set(GameRules.SPAWN_MOBS, false, server);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000");
                var serverPlayer = server.getPlayerList().getPlayers().getFirst();
                serverPlayer.getAbilities().flying = true;
                serverPlayer.teleportTo(x, y + 3, z);
                serverPlayer.setYRot(0);
                serverPlayer.setXRot(45);
                ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
                ItemStack[] stacks = {new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.STONE, 32), new ItemStack(Items.GLASS),
                        book, new ItemStack(Items.APPLE, 64), new ItemStack(Items.OAK_LOG, 3), new ItemStack(Items.COMPASS), new ItemStack(Items.STAINED_GLASS.white())};
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
            stage = stress > 0 ? 10 : 3;
            ticks = 0;
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
        Screenshot.grab(minecraft.gameDirectory, "carbon-" + name + ".png", minecraft.gameRenderer.mainRenderTarget(), 1, message -> logger.info(message.getString()));
    }
}
