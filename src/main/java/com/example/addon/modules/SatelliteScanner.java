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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;

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
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (++timer < 10) return;
        timer = 0;

        double r = range.get();
        Box box = r <= 0
            ? new Box(-3E7, -512, -3E7, 3E7, 512, 3E7)
            :
