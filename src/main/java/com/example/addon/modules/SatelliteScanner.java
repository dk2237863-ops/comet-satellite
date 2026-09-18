package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;

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
        if (mc.player == null || mc.world == null) return;
        if (++timer < 40) return;
        timer = 0;

        double r = range.get();
        var p = mc.player;

        Box box = new Box(
            p.getX() - r, p.getY() - r, p.getZ() - r,
            p.getX() + r, p.getY() + r, p.getZ() + r
        );

        for (Entity e : mc.world.getOtherEntities(p, box,
            ent -> ent != p && ent.squaredDistanceTo(p) <= r * r)) {

            String kind = e instanceof PlayerEntity ? "Player" : e.getType().toString();
            info("卫星: %s @ %.1f %.1f %.1f dist=%.1f",
                kind, e.getX(), e.getY(), e.getZ(),
                Math.sqrt(e.squaredDistanceTo(p)));
        }
    }
}
