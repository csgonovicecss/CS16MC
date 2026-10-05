package com.example.cs16minecraft.weapon;

import com.example.cs16minecraft.audio.CS16SoundManager;
import com.example.cs16minecraft.client.CS16Client;
import com.example.cs16minecraft.config.CS16Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Owns thrown grenades, HE explosions, flashbang blinding and smoke clouds. */
public final class CS16GrenadeManager {
    private static final double HE_RADIUS = 8.75;     // 350 units
    private static final double SMOKE_RADIUS = 3.6;   // ~144 units
    private static final double SMOKE_SECONDS = 18.0;
    private static final double FLASH_RANGE = 28.0;

    public final List<CS16GrenadeEntity> grenades = new ArrayList<>();
    private final List<double[]> smokes = new ArrayList<>(); // {x,y,z,endTime,nextPuff}
    private double flashStart = -100, flashHold, flashFade;

    public void throwFrom(Minecraft mc, LocalPlayer p, CS16Weapon.Grenade type) {
        // GoldSrc throw: velocity from view pitch, biased upward, plus half the player's velocity
        double pitch = p.getXRot();
        double adj = pitch < 0 ? -10 + pitch * (80.0 / 90.0) : -10 + pitch * (100.0 / 90.0);
        double speed = Math.max(15, Math.min(750, (90 - adj) * 6)) / 40.0; // blocks/s
        double yaw = Math.toRadians(p.getYRot()), pr = Math.toRadians(adj);
        Vec3 dir = new Vec3(-Math.sin(yaw) * Math.cos(pr), -Math.sin(pr), Math.cos(yaw) * Math.cos(pr));
        var c = CS16Client.MOVEMENT.controller;
        Vec3 pv = new Vec3(c.vx, c.vy, c.vz).scale(0.5 / 40.0);
        Vec3 start = p.getEyePosition().add(dir.scale(0.5)).add(0, -0.15, 0);
        grenades.add(new CS16GrenadeEntity(type, start, dir.scale(speed).add(pv)));
    }

    public void update(Minecraft mc, LocalPlayer p, double dt, double now) {
        Level level = p.level();
        Iterator<CS16GrenadeEntity> it = grenades.iterator();
        while (it.hasNext()) {
            CS16GrenadeEntity g = it.next();
            boolean boom = g.update(level, p, dt);
            if (g.bounced) {
                double v = volumeAt(p, g.pos) * 0.8;
                CS16SoundManager.INSTANCE.playAnyOf(v, "weapons/grenade_hit1.wav", "weapons/grenade_hit2.wav", "weapons/grenade_hit3.wav");
            }
            if (boom) { detonate(mc, p, g, now); it.remove(); }
        }
        Iterator<double[]> si = smokes.iterator();
        while (si.hasNext()) {
            double[] s = si.next();
            if (now >= s[3]) { si.remove(); continue; }
            if (now >= s[4]) {
                s[4] = now + 0.05;
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < 12; i++) {
                    double rx = r.nextGaussian() * SMOKE_RADIUS * 0.4, ry = r.nextGaussian() * SMOKE_RADIUS * 0.25, rz = r.nextGaussian() * SMOKE_RADIUS * 0.4;
                    level.addParticle(ParticleTypes.LARGE_SMOKE, s[0] + rx, s[1] + 0.8 + ry, s[2] + rz, 0, 0.005, 0);
                }
            }
        }
    }

    private void detonate(Minecraft mc, LocalPlayer p, CS16GrenadeEntity g, double now) {
        Level level = p.level();
        double vol = Math.max(0.15, volumeAt(p, g.pos)) * 1.0;
        switch (g.type) {
            case HE -> {
                level.addParticle(ParticleTypes.EXPLOSION_EMITTER, g.pos.x, g.pos.y + 0.2, g.pos.z, 0, 0, 0);
                CS16Damage.explode(mc, g.pos, HE_RADIUS, 100);
                CS16Damage.gunshot(mc, g.pos, 40);
                CS16SoundManager.INSTANCE.playAnyOf(vol, "weapons/sg_explode.wav", "weapons/explode3.wav", "weapons/explode4.wav", "weapons/explode5.wav");
            }
            case FLASH -> {
                CS16SoundManager.INSTANCE.playAnyOf(vol, "weapons/flashbang-1.wav", "weapons/flashbang-2.wav");
                blind(p, g.pos, now, level);
                CS16Damage.flashMobs(mc, g.pos, 22);
                CS16Damage.gunshot(mc, g.pos, 20);
            }
            case SMOKE -> {
                smokes.add(new double[]{g.pos.x, g.pos.y, g.pos.z, now + SMOKE_SECONDS, now});
                CS16SoundManager.INSTANCE.playAnyOf(vol, "weapons/sg_explode.wav");
            }
            default -> { }
        }
    }

    private void blind(LocalPlayer p, Vec3 at, double now, Level level) {
        Vec3 eye = p.getEyePosition();
        double dist = eye.distanceTo(at);
        if (dist > FLASH_RANGE) return;
        BlockHitResult r = level.clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (r.getType() != HitResult.Type.MISS && r.getLocation().distanceTo(at) > 0.5) return; // no line of sight
        Vec3 toG = at.subtract(eye).normalize();
        double facing = (p.getViewVector(1.0F).dot(toG) + 1.0) / 2.0;        // 0 = looking away, 1 = looking at it
        double near = 1.0 - dist / FLASH_RANGE;
        double total = 0.6 + 4.4 * (0.25 + 0.75 * facing) * near;
        flashStart = now;
        flashHold = total * 0.45;
        flashFade = total * 0.55;
    }

    /** 0..1 white-out strength. */
    public float flashAlpha(double now) {
        double t = now - flashStart;
        if (t < 0 || t > flashHold + flashFade) return 0f;
        if (t <= flashHold) return 1f;
        return (float) (1.0 - (t - flashHold) / flashFade);
    }

    /** 0..1 grey-out when standing inside a smoke cloud. */
    public float smokeAlpha(LocalPlayer p) {
        float best = 0f;
        Vec3 eye = p.getEyePosition();
        for (double[] s : smokes) {
            double d = eye.distanceTo(new Vec3(s[0], s[1] + 0.8, s[2]));
            if (d < SMOKE_RADIUS) best = Math.max(best, (float) Math.min(0.92, (1.0 - d / SMOKE_RADIUS) * 1.6));
        }
        return best;
    }

    private static double volumeAt(LocalPlayer p, Vec3 pos) {
        double d = p.getEyePosition().distanceTo(pos);
        return CS16Config.get().soundVolume * Math.max(0.1, 1.0 - d / 40.0);
    }
}
