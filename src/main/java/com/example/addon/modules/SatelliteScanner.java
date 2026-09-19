package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public class SatelliteScanner extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Minecraft mc = Minecraft.getInstance();

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range")
        .description("Scan radius in blocks")
        .defaultValue(64.0)
        .min(1.0)
        .sliderMax(128.0)
        .build()
    );

    private int timer = 0;

    public SatelliteScanner() {
        super(AddonTemplate.CATEGORY, "satellite-scanner", "Scans nearby entities/players and prints coordinates.");
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // 原生判断世界是否加载
        if (mc.player == null || mc.level == null) return;

        if (++timer < 40) return; // 防刷屏
        timer = 0;

        Level level = mc.level;
        double r = range.get();
        
        // 原生 AABB 范围扫描
        AABB box = mc.player.getBoundingBox().inflate(r);

        for (Entity e : level.getEntities(mc.player, box, ent -> ent != mc.player)) {
            String kind;
            // 原生精准区分玩家与实体
            if (e instanceof Player) {
                kind = "Player";
            } else {
                // 原生获取实体 ID
                kind = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            }

            info("卫星: [%s] @ [%.1f, %.1f, %.1f] 距离: [%.1f]",
                kind,
                e.getX(),
                e.getY(),
                e.getZ(),
                mc.player.distanceTo(e)
            );
        }
    }
}
