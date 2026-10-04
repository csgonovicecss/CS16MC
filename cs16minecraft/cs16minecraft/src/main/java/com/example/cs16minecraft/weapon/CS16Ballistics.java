package com.example.cs16minecraft.weapon;

import com.example.cs16minecraft.audio.CS16SoundManager;
import com.example.cs16minecraft.config.CS16Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Hitscan: spread cone, block + entity trace, CS range falloff, headshot multiplier. */
public final class CS16Ballistics {
    private static final double RANGE_BLOCKS = 128.0;
    private static final double UNITS_PER_BLOCK = 40.0;

    private CS16Ballistics() {}

    private record Hit(Entity entity, Vec3 pos, boolean block, BlockPos blockPos) {}

    public static void fire(Minecraft mc, LocalPlayer p, CS16Weapon w, double inaccuracyDeg) {
        Level level = p.level();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F);
        for (int i = 0; i < Math.max(1, w.pellets); i++) {
            Vec3 dir = spread(look, inaccuracyDeg);
            Vec3 to = eye.add(dir.scale(RANGE_BLOCKS));
            Hit h = trace(level, p, eye, to);
            if (h.entity() != null) {
                double units = eye.distanceTo(h.pos()) * UNITS_PER_BLOCK;
                double dmg = w.damage * Math.pow(w.rangeMod, units / 500.0);
                AABB box = h.entity().getBoundingBox();
                boolean head = (h.pos().y - box.minY) / Math.max(0.01, box.maxY - box.minY) > 0.82;
                if (head) dmg *= w.headMult;
                CS16Damage.hurt(mc, h.entity(), (float) (dmg * CS16Config.get().damageScale));
                level.addParticle(ParticleTypes.DAMAGE_INDICATOR, h.pos().x, h.pos().y, h.pos().z, 0, 0.05, 0);
                if (i == 0) {
                    double v = CS16Config.get().soundVolume * 0.7;
                    if (head) CS16SoundManager.INSTANCE.playAnyOf(v, "player/headshot1.wav", "player/headshot2.wav", "player/headshot3.wav");
                    else CS16SoundManager.INSTANCE.playAnyOf(v, "player/bhit_flesh-1.wav", "player/bhit_flesh-2.wav", "player/bhit_flesh-3.wav");
                }
            } else if (h.block()) {
                level.addParticle(ParticleTypes.SMOKE, h.pos().x, h.pos().y, h.pos().z, 0, 0.01, 0);
                double units = eye.distanceTo(h.pos()) * UNITS_PER_BLOCK;
                CS16Damage.damageBlock(mc, h.blockPos(), w.damage * Math.pow(w.rangeMod, units / 500.0));
            }
        }
    }

    /** @return 0 miss, 1 block, 2 entity */
    public static int melee(Minecraft mc, LocalPlayer p, double rangeBlocks, double csDamage) {
        Level level = p.level();
        Vec3 eye = p.getEyePosition();
        Vec3 to = eye.add(p.getViewVector(1.0F).scale(rangeBlocks));
        Hit h = trace(level, p, eye, to);
        if (h.entity() != null) {
            CS16Damage.hurt(mc, h.entity(), (float) (csDamage * CS16Config.get().damageScale));
            return 2;
        }
        if (h.block()) {
            CS16Damage.damageBlock(mc, h.blockPos(), csDamage);
            return 1;
        }
        return 0;
    }

    private static Hit trace(Level level, Entity self, Vec3 from, Vec3 to) {
        BlockHitResult bhr = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
        boolean blockHit = bhr.getType() != HitResult.Type.MISS;
        Vec3 end = blockHit ? bhr.getLocation() : to;
        Entity best = null;
        Vec3 bestPos = null;
        double bestD = from.distanceToSqr(end);
        AABB sweep = new AABB(from, end).inflate(1.0);
        for (Entity e : level.getEntities(self, sweep, x -> x instanceof LivingEntity && x.isAlive() && !x.isSpectator())) {
            Optional<Vec3> hit = e.getBoundingBox().inflate(0.05).clip(from, end);
            if (hit.isPresent()) {
                double d = from.distanceToSqr(hit.get());
                if (d < bestD) { bestD = d; best = e; bestPos = hit.get(); }
            }
        }
        return new Hit(best, bestPos != null ? bestPos : end, blockHit, blockHit ? bhr.getBlockPos() : null);
    }

    static Vec3 spread(Vec3 dir, double deg) {
        if (deg <= 0.0001) return dir;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = Math.toRadians(deg) * Math.sqrt(r.nextDouble());
        double phi = r.nextDouble() * Math.PI * 2;
        Vec3 up = Math.abs(dir.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = dir.cross(up).normalize();
        Vec3 up2 = right.cross(dir).normalize();
        return dir.scale(Math.cos(ang))
                .add(right.scale(Math.cos(phi) * Math.sin(ang)))
                .add(up2.scale(Math.sin(phi) * Math.sin(ang)))
                .normalize();
    }
}
