package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.world.WorldUtils;
import meteordevelopment.orbit.EventHandler;

public class SatelliteScanner extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range")
        .description("Scan radius in blocks")
        .defaultValue(64.0)
        .min(1.0)
        .sliderMax(128.0)
        .build()
    );

    private int timer;

    public SatelliteScanner() {
        super(AddonTemplate.CATEGORY, "satellite-scanner", "Scans nearby entities/players and prints coordinates.");
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Utils.canUpdate() 是 Meteor 判断世界是否加载的标准姿势
        if (!Utils.canUpdate() || mc.player == null) return;
        if (++timer < 40) return;
        timer = 0;

        double r = range.get();
        double rSq = r * r;

        // 关键修正：使用 WorldUtils.getEntities()
        for (var e : WorldUtils.getEntities()) {
            if (e == mc.player) continue;
            if (mc.player.squaredDistanceTo(e) > rSq) continue;

            // EntityUtils 的判断方法在 26.2 依然存在
            String kind = EntityUtils.isPlayer(e) ? "Player" : EntityUtils.getName(e);
            
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
