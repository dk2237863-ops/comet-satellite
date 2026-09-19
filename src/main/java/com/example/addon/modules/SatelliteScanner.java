package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.AABB;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.ArrayDeque;

public class SatelliteScanner extends Module {
    private static final int MAX_MESSAGES_PER_TICK = 10;

    private record Msg(String fmt, Object[] args) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("扫描半径（0为全局）")
        .defaultValue(0.0)
        .min(0.0)
        .build());

    private final Setting<Boolean> pearls = sgGeneral.add(new BoolSetting.Builder()
        .name("pearls").description("扫描末影珍珠").defaultValue(true).build());

    private final Setting<Boolean> shulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("shulkers").description("扫描潜影盒").defaultValue(true).build());

    private final Setting<Boolean> chests = sgGeneral.add(new BoolSetting.Builder()
        .name("chests").description("扫描箱子/陷阱箱/木桶").defaultValue(true).build());

    private final Setting<Boolean> enderChests = sgGeneral.add(new BoolSetting.Builder()
        .name("ender-chests").description("扫描末影箱").defaultValue(true).build());

    private final Setting<Boolean> hoppers = sgGeneral.add(new BoolSetting.Builder()
        .name("hoppers").description("扫描漏斗").defaultValue(true).build());

    private final Setting<Boolean> dispensers = sgGeneral.add(new BoolSetting.Builder()
        .name("dispensers").description("扫描发射器/投掷器").defaultValue(true).build());

    private final IntOpenHashSet seenPearls = new IntOpenHashSet();
    private final Object2ObjectOpenHashMap<BlockPos, String> lastLabel =
        new Object2ObjectOpenHashMap<>();

    private final ArrayDeque<Msg> pending = new ArrayDeque<>();
    private int tickTimer = 0;

    public SatelliteScanner() {
        super(AddonTemplate.CATEGORY, "satellite-scanner",
            "首次入雷达即锁定：珍珠永久静默，方块移位再报（最终版）");
    }

    @Override
    public void onActivate() {
        seenPearls.clear();
        lastLabel.clear();
        pending.clear();
        tickTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        flushMessages();
        if (++tickTimer < 10) return;
        tickTimer = 0;

        double r = range.get();
        Level world = mc.level;

        AABB box = r <= 0
            ? new AABB(-3E7, -512, -3E7, 3E7, 512, 3E7)
            : new AABB(
                mc.player.getX() - r, mc.player.getY() - r, mc.player.getZ() - r,
                mc.player.getX() + r, mc.player.getY() + r, mc.player.getZ() + r
            );

        /* ========== 末影珍珠 ========== */
        if (pearls.get()) {
            for (ThrownEnderpearl pearl : world.getEntitiesOfClass(ThrownEnderpearl.class, box)) {
                if (seenPearls.add(pearl.getId())) {
                    report("[雷达锁定-末影珍珠] X %.2f / Y %.2f / Z %.2f",
                        pearl.getX(), pearl.getY(), pearl.getZ());
                }
            }
        }

        /* ========== 方块扫描 ========== */
        boolean scanBlocks = shulkers.get() || chests.get()
            || enderChests.get() || hoppers.get() || dispensers.get();

        if (scanBlocks) {
            int radius = r <= 0 ? 80 : (int) r;
            BlockPos center = mc.player.blockPosition();

            int cxMin = (center.getX() - radius) >> 4;
            int cxMax = (center.getX() + radius) >> 4;
            int czMin = (center.getZ() - radius) >> 4;
            int czMax = (center.getZ() + radius) >> 4;

            for (int cx = cxMin; cx <= cxMax; cx++) {
                for (int cz = czMin; cz <= czMax; cz++) {
                    var chunk = world.getChunk(cx, cz);
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        BlockPos pos = be.getBlockPos();

                        double dx = pos.getX() - center.getX();
                        double dy = pos.getY() - center.getY();
                        double dz = pos.getZ() - center.getZ();
                        if (dx * dx + dy * dy + dz * dz > radius * radius) continue;

                        String label = match(be);
                        if (label == null) continue;

                        String prev = lastLabel.get(pos);
                        if (!label.equals(prev)) {
                            lastLabel.put(pos, label);
                            report("[雷达锁定-%s] [%d, %d, %d]",
                                label, pos.getX(), pos.getY(), pos.getZ());
                        }
                    }
                }
            }
        }
    }

    private void report(String fmt, Object... args) {
        pending.add(new Msg(fmt, args));
    }

    private void flushMessages() {
        for (int i = 0; i < MAX_MESSAGES_PER_TICK && !pending.isEmpty(); i++) {
            Msg m = pending.poll();
            ChatUtils.info(m.fmt(), m.args());
        }
    }

    private String match(BlockEntity be) {
        if (be instanceof ShulkerBoxBlockEntity) return shulkers.get() ? "潜影盒" : null;
        if (be instanceof EnderChestBlockEntity) return enderChests.get() ? "末影箱" : null;
        if (be instanceof HopperBlockEntity) return hoppers.get() ? "漏斗" : null;
        if (be instanceof DispenserBlockEntity || be instanceof DropperBlockEntity)
            return dispensers.get() ? "发射器/投掷器" : null;
        if (be instanceof ChestBlockEntity || be instanceof TrappedChestBlockEntity || be instanceof BarrelBlockEntity)
            return chests.get() ? "储物箱/陷阱箱/木桶" : null;
        return null;
    }
}
