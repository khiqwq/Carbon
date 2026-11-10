package first.carbon.smoketest;

import first.carbon.Carbon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
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

// 物品数量与帧率的关系，对照上游 CurseForge 页面的测试方法：超平坦世界、不可堆叠的下界合金剑落地静止、
// 关闭垂直同步且不限帧率、隐藏界面。每个数量先等物品落稳，再交替测量 Carbon 开启与关闭。
// -Dcarbon.scale=0,500,1000,2000 指定数量；-Dcarbon.scale.seconds=8 每段时长；-Dcarbon.scale.freeze=true 落稳后冻结刻。
public final class ScaleBench implements ClientModInitializer {
    private final Logger logger = LoggerFactory.getLogger("CarbonScale");
    private final String spec = System.getProperty("carbon.scale");
    private final int seconds = Integer.getInteger("carbon.scale.seconds", 8);
    private final boolean freeze = Boolean.getBoolean("carbon.scale.freeze");
    // -Dcarbon.scale.modes=on 只测开启（用于性能剖析）
    private final String[] modes = System.getProperty("carbon.scale.modes", "on,off").split(",");
    private final long[] frameTimes = new long[1 << 20];
    private int[] counts;
    private int frames, stage, ticks, index, pass;
    private long last, start;
    private boolean measuring;
    private double baseX, baseY, baseZ;
    private final double[][] results = new double[64][4];

    @Override
    public void onInitializeClient() {
        if (spec != null) {
            counts = Arrays.stream(spec.split(",")).mapToInt(Integer::parseInt).toArray();
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
            WorldRenderEvents.END_MAIN.register(context -> frame());
        }
    }

    private void frame() {
        long now = System.nanoTime();
        if (measuring && last != 0 && frames < frameTimes.length) frameTimes[frames++] = now - last;
        last = now;
    }

    private void command(Minecraft minecraft, String text) {
        var server = minecraft.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), text));
    }

    private void tick(Minecraft minecraft) {
        ticks++;
        if (stage == 0 && ticks > 40 && minecraft.getOverlay() == null && minecraft.level == null && minecraft.screen != null) {
            stage = 1;
            String name = "carbon-scale-" + System.currentTimeMillis();
            GameRules rules = new GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
            rules.set(GameRules.ADVANCE_TIME, false, null);
            rules.set(GameRules.ADVANCE_WEATHER, false, null);
            rules.set(GameRules.SPAWN_MOBS, false, null);
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(1L, false, false),
                    registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    new net.minecraft.client.gui.screens.TitleScreen());
        }
        if (stage == 1 && minecraft.player != null && minecraft.getSingleplayerServer() != null && minecraft.screen == null) {
            stage = 2;
            ticks = 0;
            minecraft.options.framerateLimit().set(260);
            minecraft.options.enableVsync().set(false);
            minecraft.getWindow().updateVsync(false);
            minecraft.options.hideGui = true;
            // 默认在无输入一段时间后限制为 30 FPS（AFK），测试期间只在最小化时限制
            minecraft.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            var server = minecraft.getSingleplayerServer();
            baseX = Math.floor(minecraft.player.getX()) + 0.5;
            baseY = Math.floor(minecraft.player.getY());
            baseZ = Math.floor(minecraft.player.getZ()) + 0.5;
            server.execute(() -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(baseX, baseY + 3, baseZ);
            });
            command(minecraft, "time set 6000");
        }
        if (stage >= 2 && minecraft.player != null) {
            minecraft.player.setYRot(0);
            minecraft.player.setXRot(25);
        }
        // 每个数量：清空、生成、落稳（可选冻结）、交替测量开启与关闭
        if (stage == 2 && ticks == 60) {
            if (index == counts.length) {
                report();
                stage = 9;
                minecraft.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Carbon scale benchmark finished"));
                return;
            }
            command(minecraft, "tick unfreeze");
            command(minecraft, "kill @e[type=item]");
            spawn(minecraft, counts[index]);
            stage = 3;
            ticks = 0;
        }
        if (stage == 3 && ticks == 100) {
            if (freeze) command(minecraft, "tick freeze");
            pass = 0;
            startPass();
        }
        if (stage == 4 && ticks == 30) {
            logger.info("SCALE measuring items={} carbon={}", counts[index], passEnabled());
            measuring = true;
            frames = 0;
            last = 0;
            start = System.nanoTime();
        }
        if (stage == 4 && ticks == 30 + seconds * 20) finishPass(minecraft);
        if (stage == 9 && minecraft.level == null && minecraft.getSingleplayerServer() == null) {
            stage = 10;
            minecraft.stop();
        }
    }

    // 奇数序号先测关闭，抵消顺序影响
    private boolean passEnabled() {
        if (modes.length == 1) return modes[0].equals("on");
        return (pass == 0) == (index % 2 == 0);
    }

    private void startPass() {
        Carbon.CONFIG.enabled.set(passEnabled());
        stage = 4;
        ticks = 0;
    }

    private void finishPass(Minecraft minecraft) {
        measuring = false;
        double elapsed = (System.nanoTime() - start) / 1e9;
        long[] sorted = Arrays.copyOf(frameTimes, frames);
        Arrays.sort(sorted);
        int slowest = Math.max(1, frames / 100);
        double slowTotal = 0;
        for (int i = frames - slowest; i < frames; i++) slowTotal += sorted[i];
        double average = frames / elapsed, low = slowest * 1e9 / slowTotal;
        boolean enabled = passEnabled();
        results[index][enabled ? 0 : 2] = average;
        results[index][enabled ? 1 : 3] = low;
        logger.info("SCALE items={} carbon={} frames={} avgFps={} onePercentLow={} entities={} stats: {}", counts[index], enabled, frames,
                String.format("%.1f", average), String.format("%.1f", low), minecraft.level.getEntityCount(), Carbon.RENDERER.statistics());
        if (++pass < modes.length) startPass();
        else {
            Carbon.CONFIG.enabled.set(true);
            index++;
            stage = 2;
            ticks = 0;
        }
    }

    private void report() {
        StringBuilder table = new StringBuilder("SCALE RESULT freeze=" + freeze + "\n  items | carbon avg / 1% low | vanilla avg / 1% low | ratio");
        for (int i = 0; i < counts.length; i++) {
            double[] r = results[i];
            table.append(String.format("%n  %5d | %8.1f / %7.1f | %8.1f / %7.1f | x%.2f", counts[i], r[0], r[1], r[2], r[3], r[2] == 0 ? 0 : r[0] / r[2]));
        }
        logger.info(table.toString());
    }

    // 不可堆叠的剑不会合并；网格间距 0.5 格，位于视线前方的平地上
    private void spawn(Minecraft minecraft, int count) {
        var server = minecraft.getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            int side = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
            for (int i = 0; i < count; i++) {
                double x = baseX - side * 0.25 + (i % side) * 0.5, z = baseZ + 4 + (i / side) * 0.5;
                ItemEntity item = new ItemEntity(level, x, baseY + 0.5, z, new ItemStack(Items.NETHERITE_SWORD), 0, 0, 0);
                item.setPickUpDelay(32767);
                item.setUnlimitedLifetime();
                level.addFreshEntity(item);
            }
        });
    }
}
