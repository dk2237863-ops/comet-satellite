package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.block.entity.*;

import java.util.HashSet;
import java.util.Set;

public class SatelliteScanner extends Module {

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range")
        .description("雷达扫描半径（0=全加载区块）")
        .defaultValue(48.0)
        .min(0.0)
        .sliderMax(128.0)
        .build()
    );

    private final Setting<Boolean> pearls      = sg.add(new BoolSetting.Builder().name("ender-pearls").defaultValue(true).build());
    private final Setting<Boolean> shulkers    = sg.add(new BoolSetting.Builder().name("shulker-boxes").defaultValue(true).build());
    private final Setting<Boolean> chests      = sg.add(new BoolSetting.Builder().name("chests").defaultValue(true).build());
    private final Setting<Boolean> enderChests = sg.add(new BoolSetting.Builder().name("ender-chests").defaultValue(true).build());
    private final Setting<Boolean> hoppers     = sg.add(new BoolSetting.Builder().name("hoppers").defaultValue(true).build());
    private final Setting<Boolean> dispensers  = sg.add(new BoolSetting.Builder().name("dispensers-droppers").defaultValue(true).build());

    private final Set<Integer> seenPearls = new HashSet<>();
    private final Set<BlockPos> seenBlocks = new HashSet<>();
    private int tickTimer = 0;

    public SatelliteScanner() {
        super(
            AddonTemplate.CATEGORY,
            "satellite-scanner",
            "首次入雷达即锁定：珍珠永久静默，方块移位再报"
        );
    }

    @Override
    public void onActivate() {
        seenPearls.clear();
        seenBlocks.clear();
        tickTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        if (++tickTimer < 10) return;
        tickTimer = 0;

        double r = range.get();
        Box box = r <= 0
            ? new Box(-3E7, -512, -3E7, 3E7, 512, 3E7)
            : new Box(
                mc.player.getX() - r,
                mc.player.getY() - r,
                mc.player.getZ() - r,
                mc.player.getX() + r,
                mc.player.getY() + r,
                mc.player.getZ() + r
            );

        World world = mc.world;

        /* ========== 末影珍珠 ========== */
        if (pearls.get()) {
            for (Entity e : world.getEntitiesByClass(EnderPearlEntity.class, box, ent -> true)) {
                int id = e.getId();
                if (seenPearls.add(id)) {
                    info("[雷达锁定-末影珍珠] X %.2f / Y %.2f / Z %.2f",
                        e.getX(), e.getY(), e.getZ());
                }
            }
        }

        /* ========== 方块 ========== */
        boolean scanBlocks =
            shulkers.get() || chests.get() || enderChests.get() || hoppers.get() || dispensers.get();

        if (scanBlocks) {
            int radius = r <= 0 ? 80 : (int) r;
            BlockPos center = mc.player.getBlockPos();

            for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = center.add(dx, dy, dz);
                BlockEntity be = world.getBlockEntity(pos);
                if (be == null) continue;

                String label = match(be);
                if (label == null) continue;

                if (seenBlocks.add(pos)) {
                    info("[雷达锁定-%s] [%d, %d, %d]",
                        label, pos.getX(), pos.getY(), pos.getZ());
                }
            }
        }
    }

    private String match(BlockEntity be) {
        if (be instanceof ShulkerBoxBlockEntity)
            return shulkers.get() ? "潜影盒" : null;
        if (be instanceof EnderChestBlockEntity)
            return enderChests.get() ? "末影箱" : null;
        if (be instanceof HopperBlockEntity)
            return hoppers.get() ? "漏斗" : null;
        if (be instanceof DispenserBlockEntity || be instanceof DropperBlockEntity)
            return dispensers.get() ? "发射器/投掷器" : null;
        if (be instanceof ChestBlockEntity ||
            be instanceof TrappedChestBlockEntity ||
            be instanceof BarrelBlockEntity)
            return chests.get() ? "储物箱/陷阱箱/木桶" : null;
        return null;
    }
}
