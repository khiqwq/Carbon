package first.carbon.smoketest;

import first.carbon.Carbon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

// 帧率对比：无掉落物与大量掉落物场景下分别测量 Carbon 开启与关闭的平均帧率和 1% low。
public final class Benchmark implements ClientModInitializer {
    private final Logger logger = LoggerFactory.getLogger("CarbonBenchmark");
    private final int items = Integer.getInteger("carbon.bench.items", 1000);
    private final int seconds = Integer.getInteger("carbon.bench.seconds", 15);
    private final String[] phases = System.getProperty("carbon.bench.phases", "empty-off,empty-on,empty-off-2,empty-on-2,items-off,items-on,items-off-2,items-on-2").split(",");
    private final long[] frameTimes = new long[1 << 20];
    private int frames;
    private long last, phaseStart;
    private long[] gcStart = new long[2];
    private boolean measuring;
    private int stage, ticks, phase;

    @Override
    public void onInitializeClient() {
        if (Boolean.getBoolean("carbon.bench")) {
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
            LevelRenderEvents.END_MAIN.register(context -> frame());
        }
    }

    private void frame() {
        long now = System.nanoTime();
        if (measuring && last != 0 && frames < frameTimes.length) frameTimes[frames++] = now - last;
        last = now;
    }

    private void tick(Minecraft minecraft) {
        ticks++;
        if (stage == 0 && ticks > 40 && minecraft.getOverlay() == null && minecraft.level == null && minecraft.screen != null) {
            stage = 1;
            String name = "carbon-bench-" + System.currentTimeMillis();
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(1L, true, false),
                    registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
                    new net.minecraft.client.gui.screens.TitleScreen());
        }
        if (stage == 1 && minecraft.player != null && minecraft.getSingleplayerServer() != null && minecraft.screen == null) {
            stage = 2;
            ticks = 0;
            minecraft.options.framerateLimit().set(Integer.getInteger("carbon.bench.fps", 260));
            minecraft.options.enableVsync().set(false);
            var server = minecraft.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                var rules = level.getGameRules();
                rules.set(GameRules.ADVANCE_TIME, false, server);
                rules.set(GameRules.ADVANCE_WEATHER, false, server);
                rules.set(GameRules.SPAWN_MOBS, false, server);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000");
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(player.getX(), player.getY() + 4, player.getZ());
                player.setYRot(0);
                player.setXRot(30);
            });
            minecraft.player.setYRot(0);
            minecraft.player.setXRot(30);
            Carbon.CONFIG.enabled.set(!phases[0].contains("off"));
            if (phases[0].startsWith("items")) spawnItems(minecraft);
        }
        // 区块加载与预热 45 秒后开始依次测量，开关交替以抵消顺序影响
        if (stage == 2 && minecraft.player != null) {
            minecraft.player.setYRot(0);
            minecraft.player.setXRot(30);
        }
        // fly-* 阶段：以 30 格/秒水平飞行，持续加载新区块
        if ((stage == 2 || stage == 3) && phases[phase].startsWith("fly") && minecraft.getSingleplayerServer() != null) {
            var server = minecraft.getSingleplayerServer();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(player.getX() + 1.5, 120, player.getZ());
            });
        }
        if (stage == 2 && ticks == 900) startPhase(minecraft);
        if (stage == 3 && ticks == seconds * 20) {
            finishPhase(minecraft);
            phase++;
            if (phase == phases.length) {
                stage = 4;
                minecraft.disconnectFromWorld(Component.literal("Carbon benchmark finished"));
            } else {
                Carbon.CONFIG.enabled.set(!phases[phase].contains("off"));
                if (phases[phase].equals("items-off")) spawnItems(minecraft);
                stage = 2;
                ticks = phases[phase].equals("items-off") ? 500 : 700;
            }
        }
        if (stage == 4 && minecraft.level == null && minecraft.getSingleplayerServer() == null) {
            stage = 5;
            minecraft.stop();
        }
    }

    private void startPhase(Minecraft minecraft) {
        stage = 3;
        ticks = 0;
        frames = 0;
        last = 0;
        measuring = true;
        gcStart = gcTotals();
        phaseStart = System.nanoTime();
    }

    // 仅统计停顿型收集器（排除 G1 并发周期与 ZGC Cycles）
    private long[] gcTotals() {
        long count = 0, time = 0;
        for (var bean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!bean.getName().contains("Concurrent") && !bean.getName().contains("Cycles")) {
                count += Math.max(0, bean.getCollectionCount());
                time += Math.max(0, bean.getCollectionTime());
            }
        }
        return new long[]{count, time};
    }

    private void finishPhase(Minecraft minecraft) {
        measuring = false;
        double elapsed = (System.nanoTime() - phaseStart) / 1e9;
        long[] sorted = Arrays.copyOf(frameTimes, frames);
        Arrays.sort(sorted);
        int slowest = Math.max(1, frames / 100);
        double slowTotal = 0;
        for (int i = frames - slowest; i < frames; i++) slowTotal += sorted[i];
        double low = slowest * 1e9 / slowTotal;
        int stalls = 0;
        for (int i = 0; i < frames; i++) if (frameTimes[i] >= 50_000_000L) stalls++;
        long[] gc = gcTotals();
        logger.info("BENCH {} enabled={} frames={} avgFps={} onePercentLow={} stalls50={} maxMs={} gcPauses={} gcMs={} entities={} carbon: {}", phases[phase], Carbon.CONFIG.enabled.get(), frames,
                String.format("%.1f", frames / elapsed), String.format("%.1f", low), stalls, String.format("%.1f", frames == 0 ? 0 : sorted[frames - 1] / 1e6),
                gc[0] - gcStart[0], gc[1] - gcStart[1], minecraft.level.getEntityCount(), Carbon.RENDERER.statistics());
        Screenshot.grab(minecraft.gameDirectory, "bench-" + phases[phase] + ".png", minecraft.getMainRenderTarget(), 1, message -> { });
    }

    private void spawnItems(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        logger.info("BENCH spawn {}", items);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            ItemStack[] stacks = {new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 12), new ItemStack(Items.ROTTEN_FLESH, 3), new ItemStack(Items.BONE, 2),
                    new ItemStack(Items.ARROW, 7), new ItemStack(Items.OAK_LOG, 16), new ItemStack(Items.WHEAT_SEEDS, 5), new ItemStack(Items.IRON_INGOT),
                    new ItemStack(Items.STRING, 4), new ItemStack(Items.GUNPOWDER, 2), new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.ENCHANTED_BOOK)};
            int side = (int) Math.ceil(Math.sqrt(items));
            for (int i = 0; i < items; i++) {
                double x = player.getX() - side / 2.0 + (i % side) * 0.6, z = player.getZ() + 4 + (i / side) * 0.6;
                ItemEntity item = new ItemEntity(level, x, player.getY() + 1, z, stacks[i % stacks.length].copy(), 0, 0, 0);
                item.setPickUpDelay(32767);
                item.setUnlimitedLifetime();
                level.addFreshEntity(item);
            }
        });
    }
}
