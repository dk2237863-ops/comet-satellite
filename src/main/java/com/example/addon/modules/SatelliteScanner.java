package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

import java.util.HashSet;
import java.util.Set;

public class SatelliteScanner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("扫描半径（0为全加载区域）")
        .defaultValue(48.0)
        .min(0.0)
        .build());

    private final Setting<Boolean> pearls = sgGeneral.add(new BoolSetting.Builder()
        .name("ender-pearls")
        .description("锁定首次进入雷达的末影珍珠，之后不再提示")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> shulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-boxes")
        .description("扫描潜影盒")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> chests = sgGeneral.add(new BoolSetting.Builder()
        .name("chests")
        .description("扫描箱子、陷阱箱、木桶")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> enderChests = sgGeneral.add(new BoolSetting.Builder()
        .name("ender-chests")
        .description("扫描末影箱")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> hoppers = sgGeneral.add(new BoolSetting.Builder()
        .name("hoppers")
        .description("扫描漏斗")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> dispensers = sgGeneral.add(new BoolSetting.Builder()
        .name("dispensers-droppers")
        .description("扫描发射器、投掷器")
        .defaultValue(true)
        .build());

    private final Set<Integer> seenPearlIds = new HashSet<>();
    private final Set<BlockPos> seenBlockPos = new HashSet<>();
    private int tickTimer = 0;

    public SatelliteScanner() {
        super(
            AddonTemplate.CATEGORY,
            "satellite-scanner",
            "雷达扫描：目标首次进入视野即锁定，珍珠永久静默，方块移位后新坐标再报"
        );
    }

    @Override
    public void onActivate() {
        seenPearlIds.clear();
        seenBlockPos.clear();
        tickTimer = 0;
        ChatUtils.info("卫星扫描器已开启");
    }

    @Override
    public void onDeactivate() {
        ChatUtils.info("卫星扫描器已关闭");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (++tickTimer < 10) return;
        tickTimer = 0;

        double r = range.get();
        // 扫描区域：r≤0扫全图，否则扫以玩家为中心的r半径
        AABB scanArea = r <= 0
            ? new AABB(-3E7, -512, -3E7, 3E7, 512, 3E7)
            : new AABB(
                mc.player.getX() - r, mc.player.getY() - r, mc.player.getZ() - r,
                mc.player.getX() + r, mc.player.getY() + r, mc.player.getZ() + r
            );

        Level level = mc.level;

        /* ========== 末影珍珠：首次入雷达永久锁定 ========== */
        if (pearls.get()) {
            // 直接按末影珍珠类获取，Mojang映射标准写法，无需EntityType
            for (ThrownEnderpearl pearl : level.getEntitiesOfClass(ThrownEnderpearl.class, scanArea)) {
                int pearlId = pearl.getId();
                if (seenPearlIds.add(pearlId)) {
                    ChatUtils.info("[雷达锁定-末影珍珠] 首次坐标: X %.2f / Y %.2f / Z %.2f",
                        pearl.getX(), pearl.getY(), pearl.getZ());
                }
            }
        }

        /* ========== 方块：新坐标首次出现才报，移位再报 ========== */
        boolean scanBlocks = shulkers.get() || chests.get() || enderChests.get() || hoppers.get() || dispensers.get();
        if (scanBlocks) {
            int radius = r <= 0 ? 80 : (int) r;
            BlockPos playerPos = mc.player.blockPosition();

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = playerPos.offset(dx, dy, dz);
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be == null) continue;

                        String label = matchBlock(be);
                        if (label == null) continue;

                        if (seenBlockPos.add(pos)) {
                            ChatUtils.info("[雷达锁定-%s] 首次坐标: [%d, %d, %d]",
                                label, pos.getX(), pos.getY(), pos.getZ());
                        }
                    }
                }
            }
        }
    }

    private String matchBlock(BlockEntity be) {
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
