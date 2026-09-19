package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.block.entity.*;

import java.util.HashSet;
import java.util.Set;

public class SatelliteScanner extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range").description("雷达半径（0=全图）").defaultValue(48.0).min(0.0).sliderMax(128.0).build());

    private final Setting<Boolean> pearls    = sg.add(new BoolSetting.Builder().name("ender-pearls").defaultValue(true).build());
    private final Setting<Boolean> shulkers   = sg.add(new BoolSetting.Builder().name("shulker-boxes").defaultValue(true).build());
    private final Setting<Boolean> chests     = sg.add(new BoolSetting.Builder().name("chests-traps-barrels").defaultValue(true).build());
    private final Setting<Boolean> enderChest = sg.add(new BoolSetting.Builder().name("ender-chests").defaultValue(true).build());
    private final Setting<Boolean> hoppers    = sg.add(new BoolSetting.Builder().name("hoppers").defaultValue(true).build());
    private final Setting<Boolean> redstone   = sg.add(new BoolSetting.Builder().name("dispensers-droppers").defaultValue(true).build());

    // 珍珠按实体ID永久拉黑，方块按坐标记忆（搬新位=新坐标再报）
    private final Set<Integer>  pearlSeen = new HashSet<>();
    private final Set<BlockPos> blockSeen = new HashSet<>();
    private int timer = 0;

    public SatelliteScanner() {
        super(AddonTemplate.CATEGORY, "satellite-scanner", "雷达：目标首次进入视野报一次，方块移位再报");
    }

    @Override
    public void onActivate() {
        pearlSeen.clear();
        blockSeen.clear();
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (++timer < 10) return;
        timer = 0;

        double r = range.get();

        // ===== 末影珍珠：见一次永久不管 =====
        if (pearls.get()) {
            var box = (r <= 0)
                ? mc.level.getEntities().getAll()
                : mc.level.getEntities(mc.player,
                    new net.minecraft.world.phys.AABB(
                        mc.player.getX()-r, mc.player.getY()-r, mc.player.getZ()-r,
                        mc.player.getX()+r, mc.player.getY()+r, mc.player.getZ()+r),
                    e -> e instanceof ThrownEnderpearl);
            for (Entity e : box) {
                if (pearlSeen.add(e.getId())) {
                    info("[珍珠-首次入雷达] X %.2f / Y %.2f / Z %.2f", e.getX(), e.getY(), e.getZ());
                }
            }
        }

        // ===== 方块：新坐标才报，移位自然再爆 =====
        boolean anyBlock = shulkers.get() || chests.get() || enderChest.get() || hoppers.get() || redstone.get();
        if (anyBlock) {
            int ri = r <= 0 ? 80 : r.intValue();
            BlockPos c = mc.player.blockPosition();
            for (int dx = -ri; dx <= ri; dx++)
            for (int dy = -ri; dy <= ri; dy++)
            for (int dz = -ri; dz <= ri; dz++) {
                BlockPos p = c.offset(dx, dy, dz);
                BlockEntity be = mc.level.getBlockEntity(p);
                if (be == null) continue;
                String lbl = match(be);
                if (lbl == null) continue;
                if (blockSeen.add(p)) {
                    info("[%s-首次入雷达] [%d, %d, %d]", lbl, p.getX(), p.getY(), p.getZ());
                }
            }
        }
    }

    private String match(BlockEntity be) {
        if (be instanceof ShulkerBoxBlockEntity) return shulkers.get() ? "潜影盒" : null;
        if (be instanceof EnderChestBlockEntity) return enderChest.get() ? "末影箱" : null;
        if (be instanceof HopperBlockEntity)    return hoppers.get() ? "漏斗" : null;
        if (be instanceof DispenserBlockEntity || be instanceof DropperBlockEntity)
            return redstone.get() ? "发射器/投掷器" : null;
        if (be instanceof ChestBlockEntity || be instanceof TrappedChestBlockEntity || be instanceof BarrelBlockEntity)
            return chests.get() ? "箱/陷阱箱/木桶" : null;
        return null;
    }
}
