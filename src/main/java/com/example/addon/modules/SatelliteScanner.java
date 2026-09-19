package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

// Yarn映射下的正确导入（Fabric默认用这个）
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity; // 末影珍珠Yarn类名
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.client.world.ClientWorld;
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
        .build());

    // 功能开关（完全按你要的列表）
    private final Setting<Boolean> pearls = sg.add(new BoolSetting.Builder()
        .name("ender-pearls")
        .description("末影珍珠首次入雷达锁定")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> shulkers = sg.add(new BoolSetting.Builder()
        .name("shulker-boxes")
        .description("潜影盒")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> chests = sg.add(new BoolSetting.Builder()
        .name("chests")
        .description("箱子/陷阱箱/木桶")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> enderChests = sg.add(new BoolSetting.Builder()
        .name("ender-chests")
        .description("末影箱")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> hoppers = sg.add(new BoolSetting.Builder()
        .name("hoppers")
        .description("漏斗")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> dispensers = sg.add(new BoolSetting.Builder()
        .name("dispensers-droppers")
        .description("发射器/投掷器")
        .defaultValue(true)
        .build());

    // 去重逻辑：珍珠按实体ID永久拉黑，方块按坐标记录
    private final Set<Integer> seenPearlIds = new HashSet<>();
    private final Set<BlockPos> seenBlockPos = new HashSet<>();
    private int timer = 0;

    public SatelliteScanner() {
        super(AddonTemplate.CATEGORY, "satellite-scanner", "首次入雷达报坐标，方块移位再报，珍珠永久静默");
    }

    @Override
    public void onActivate() {
        seenPearlIds.clear();
        seenBlockPos.clear();
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Yarn下客户端世界是mc.world，不是mc.level！
        ClientWorld world = mc.world;
        if (mc.player == null || world == null) return;
        if (++timer < 10) return; // 0.5秒扫一次，不刷屏
        timer = 0;

        double r = range.get();
        // 构建扫描范围：r=0时扫全加载区块
        Box scanBox = r <= 0 ?
            new Box(-3E7, -512, -3E7, 3E7, 512, 3E7) :
            new Box(
                mc.player.getX() - r, mc.player.getY() - r, mc.player.getZ() - r,
                mc.player.getX() + r, mc.player.getY() + r, mc.player.getZ() + r
            );

        // ===== 末影珍珠：第一次进雷达就报，之后永久不管 =====
        if (pearls.get()) {
            // Yarn下用getEntitiesByClass直接拿所有末影珍珠，不用无参调用
            for (EnderPearlEntity pearl : world.getEntitiesByClass(EnderPearlEntity.class, scanBox, e -> true)) {
                int pearlId = pearl.getId();
                if (seenPearlIds.add(pearlId)) { // 第一次见到这颗珍珠
                    info("[雷达捕获-末影珍珠] 首次坐标: X %.2f / Y %.2f / Z %.2f",
                        pearl.getX(), pearl.getY(), pearl.getZ());
                }
                // 之后这颗珍珠飞到哪、落地消失都不再提示
            }
        }

        // ===== 方块：新坐标首次出现报，挪到新位置再报 =====
        boolean scanBlocks = shulkers.get() || chests.get() || enderChests.get() || hoppers.get() || dispensers.get();
        if (scanBlocks) {
            int scanRadius = r <= 0 ? 80 : (int) r; // 修复double转int的错误，不用intValue()
            BlockPos playerPos = mc.player.getBlockPos();
            for (int dx = -scanRadius; dx <= scanRadius; dx++) {
                for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                    for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                        BlockPos pos = playerPos.add(dx, dy, dz);
                        BlockEntity be = world.getBlockEntity(pos);
                        if (be == null) continue;
                        String label = matchBlock(be);
                        if (label == null) continue;
                        // 该坐标第一次出现才报，挪到新坐标算新目标
                        if (seenBlockPos.add(pos)) {
                            info("[雷达捕获-%s] 首次坐标: [%d, %d, %d]",
                                label, pos.getX(), pos.getY(), pos.getZ());
                        }
                    }
                }
            }
        }
    }

    // 按开关匹配方块类型
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
