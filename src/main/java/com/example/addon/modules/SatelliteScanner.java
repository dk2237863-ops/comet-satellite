package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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
        .name("pearls")
        .description("扫描末影珍珠")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> shulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("shulkers")
        .description("扫描潜影盒")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> chests = sgGeneral.add(new BoolSetting.Builder()
        .name("chests")
        .description("扫描箱子/陷阱箱/木桶")
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
        .name("dispensers")
        .description("扫描发射器/投掷器")
        .defaultValue(true)
        .build());

    private final Set<Integer> seenPearls = new HashSet<>();
    private final Set<BlockPos> seenBlocks = new HashSet<>();
    private final ArrayDeque<Msg> pending = new ArrayDeque<>();
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
        AABB box = r <= 0
            ? new AABB(-3E7, -512, -3E7, 3E7, 512, 3E7)
            : new AABB(
                mc.player.getX() - r, mc.player.getY() - r, mc.player.getZ() - r,
                mc.player.getX() + r, mc.player.getY() + r, mc.player.getZ() + r
            );

        Level world = mc.level;

        /* ========== 末影珍珠 ========== */
        if (pearls.get()) {
            for (Entity e : world.getEntitiesOfClass(Entity.class, box, ent -> ent.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("enderpearl"))) {
                int id = e.getId();
                if (seenPearls.add(id)) {
                    report("[雷达锁定-末影珍珠] X %.2f / Y %.2f / Z %.2f",
                        e.getX(), e.getY(), e.getZ());
                }
            }
        }

        /* ========== 方块（按区块遍历已加载的方块实体） ========== */
        boolean scanBlocks =
            shulkers.get() || chests.get() || enderChests.get() || hoppers.get() || dispensers.get();

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
                        if (Math.abs(pos.getX() - center.getX()) > radius) continue;
                        if (Math.abs(pos.getY() - center.getY()) > radius) continue;
                        if (Math.abs(pos.getZ() - center.getZ()) > radius) continue;

                        String label = match(be);
                        if (label == null) continue;

                        if (seenBlocks.add(pos)) {
                            report("[雷达锁定-%s] [%d, %d, %d]",
                                label, pos.getX(), pos.getY(), pos.getZ());
                        }
                    }
                }
            }
        }
    }

    /* 消息先入队，再按每 tick 上限发出，保证不丢 */
    private void report(String fmt, Object... args) {
        pending.add(new Msg(fmt, args));
    }

    private void flushMessages() {
        for (int i = 0; i < MAX_MESSAGES_PER_TICK && !pending.isEmpty(); i++) {
            Msg m = pending.poll();
            info(m.fmt(), m.args());
        }
    }

    private String match(BlockEntity be) {
        if (be instanceof ShulkerBoxBlockEntity)   return shulkers.get()    ? "潜影盒" : null;
        if (be instanceof EnderChestBlockEntity)  return enderChests.get() ? "末影箱" : null;
        if (be instanceof HopperBlockEntity)      return hoppers.get()     ? "漏斗" : null;
        if (be instanceof DispenserBlockEntity || be instanceof DropperBlockEntity)     return dispensers.get()  ? "发射器/投掷器" : null;
        if (be instanceof ChestBlockEntity || be instanceof TrappedChestBlockEntity || be instanceof BarrelBlockEntity)      return chests.get()      ? "储物箱/陷阱箱/木桶" : null;
        return null;
    }
}
