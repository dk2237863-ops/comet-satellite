package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;

public class SatelliteScanner extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range")
        .description("Scan radius in blocks")
        .defaultValue(64)
        .min(1)
        .sliderMax(128)
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
        if (!Utils.canUpdate() || mc.player == null) return;
        if (++timer < 40) return;
        timer = 0;

        double r = range.get();
        double r2 = r * r;

        for (var e : EntityUtils.getEntities()) {
            if (e == mc.player) continue;
            if (mc.player.squaredDistanceTo(e) > r2) continue;

            String kind = EntityUtils.isPlayer(e) ? "Player" : EntityUtils.getName(e);
            info("卫星: %s @ %.1f %.1f %.1f dist=%.1f",
                kind,
                e.getX(), e.getY(), e.getZ(),
                PlayerUtils.distanceToCamera(e)
            );
        }
    }
}
