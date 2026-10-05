package com.example.cs16minecraft.weapon;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A thrown grenade: simple bouncing physics against the Minecraft world (blocks), 1.6s fuse.
 * Kept as a lightweight simulated object (not a registered Minecraft entity) so it needs no networking/registry.
 */
public final class CS16GrenadeEntity {
    public final CS16Weapon.Grenade type;
    public Vec3 pos, vel;
    public double age;
    public final double fuse = 1.6;
    public boolean bounced;

    public CS16GrenadeEntity(CS16Weapon.Grenade type, Vec3 pos, Vec3 vel) {
        this.type = type;
        this.pos = pos;
        this.vel = vel;
    }

    /** @return true once the fuse has run out. Velocity is in blocks/second. */
    public boolean update(Level level, Entity owner, double dt) {
        age += dt;
        bounced = false;
        vel = vel.add(0, -20.0 * dt, 0); // 800 units/s^2
        Vec3 next = pos.add(vel.scale(dt));
        BlockHitResult r = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        if (r.getType() != HitResult.Type.MISS) {
            Vec3 n = new Vec3(r.getDirection().getStepX(), r.getDirection().getStepY(), r.getDirection().getStepZ());
            double vn = vel.dot(n);
            Vec3 vt = vel.subtract(n.scale(vn));
            double friction = n.y > 0.5 ? 0.78 : 0.9;
            vel = vt.scale(friction).subtract(n.scale(vn * 0.45));
            pos = r.getLocation().add(n.scale(0.04));
            bounced = Math.abs(vn) > 2.0;
            if (n.y > 0.5 && vel.length() < 0.8) vel = Vec3.ZERO;
        } else {
            pos = next;
        }
        return age >= fuse;
    }
}
