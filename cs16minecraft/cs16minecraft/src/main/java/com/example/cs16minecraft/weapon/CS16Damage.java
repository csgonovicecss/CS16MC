package com.example.cs16minecraft.weapon;

import com.example.cs16minecraft.config.CS16Config;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class CS16Damage {
    private CS16Damage() {}

    private static final Map<Integer, Long> PANIC = new HashMap<>();

    public static void gunshot(Minecraft mc, Vec3 pos, double radius) {
        var srv = mc.getSingleplayerServer();

        if (!CS16Config.get().mobAlerts || srv == null || mc.player == null || radius < 2) {
            return;
        }

        UUID pid = mc.player.getUUID();

        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);
            if (sp == null) return;

            ServerLevel lvl = (ServerLevel) sp.level();
            long now = System.currentTimeMillis();

            for (Mob mob : lvl.getEntitiesOfClass(
                    Mob.class,
                    new AABB(pos, pos).inflate(radius))) {

                double d = mob.position().distanceTo(pos);

                if (d > radius) continue;

                String kind = mob.getType().getDescriptionId();

                if (kind.equals("entity.minecraft.villager")
                        || kind.equals("entity.minecraft.wandering_trader")
                        || kind.equals("entity.minecraft.cat")) {

                    PANIC.put(mob.getId(), now + 5000);

                    double dx = mob.getX() - sp.getX();
                    double dz = mob.getZ() - sp.getZ();

                    double length = Math.sqrt(dx * dx + dz * dz);

                    if (length < 0.01) {
                        dx = 1;
                        dz = 0;
                        length = 1;
                    }

                    dx /= length;
                    dz /= length;

                    double distance = 6.0;

                    double targetX = mob.getX() + dx * distance;
                    double targetZ = mob.getZ() + dz * distance;

                    mob.getNavigation().moveTo(
                            targetX,
                            mob.getY(),
                            targetZ,
                            1.0
                    );

                } else if (mob instanceof Monster) {

                    if (d <= radius * 0.6) {
                        mob.setTarget(sp);
                    } else {
                        mob.getNavigation().moveTo(
                                pos.x,
                                pos.y,
                                pos.z,
                                1.2
                        );
                    }
                }
            }
        });
    }

    public static void tickPanic(Minecraft mc) {
        var srv = mc.getSingleplayerServer();

        if (srv == null || mc.player == null) return;

        UUID pid = mc.player.getUUID();

        srv.execute(() -> {
            if (PANIC.isEmpty()) return;

            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);

            if (sp == null) return;

            ServerLevel lvl = (ServerLevel) sp.level();
            long now = System.currentTimeMillis();

            var it = PANIC.entrySet().iterator();

            while (it.hasNext()) {
                var e = it.next();

                Entity ent = lvl.getEntity(e.getKey());

                if (!(ent instanceof Mob mob)
                        || !mob.isAlive()
                        || now >= e.getValue()) {

                    it.remove();
                    continue;
                }

                double dx = mob.getX() - sp.getX();
                double dz = mob.getZ() - sp.getZ();

                double length = Math.sqrt(dx * dx + dz * dz);

                if (length < 0.01) {
                    dx = 1;
                    dz = 0;
                    length = 1;
                }

                dx /= length;
                dz /= length;

                if (mob.getNavigation().isDone()) {
                    double distance = 5.0 + Math.random() * 3.0;

                    double targetX = mob.getX() + dx * distance;
                    double targetZ = mob.getZ() + dz * distance;

                    mob.getNavigation().moveTo(
                            targetX,
                            mob.getY(),
                            targetZ,
                            1.0
                    );
                }
            }
        });
    }

    private static final Map<Long, double[]> BLOCK_HP = new HashMap<>();

    public static void damageBlock(Minecraft mc, BlockPos pos, double csDamage) {
        CS16Config cfg = CS16Config.get();
        var srv = mc.getSingleplayerServer();

        if (!cfg.blockDamage || srv == null || mc.player == null || csDamage <= 0) {
            return;
        }

        UUID pid = mc.player.getUUID();
        BlockPos p = pos.immutable();

        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);

            if (sp == null) return;

            ServerLevel lvl = (ServerLevel) sp.level();
            BlockState st = lvl.getBlockState(p);
            float hardness = st.getDestroySpeed(lvl, p);

            if (st.isAir() || hardness < 0 || !st.getFluidState().isEmpty()) {
                return;
            }

            long now = System.currentTimeMillis();

            BLOCK_HP.values().removeIf(
                    v -> now - (long) v[1] > 15000
            );

            double[] e = BLOCK_HP.computeIfAbsent(
                    p.asLong(),
                    k -> new double[]{0, now}
            );

            e[0] += csDamage;
            e[1] = now;

            double max = Math.max(
                    5.0,
                    hardness * cfg.blockHpPerHardness
            );

            int breaker = p.hashCode();

            if (e[0] >= max) {
                BLOCK_HP.remove(p.asLong());
                lvl.destroyBlockProgress(breaker, p, -1);
                lvl.destroyBlock(p, false, sp);
            } else {
                lvl.destroyBlockProgress(
                        breaker,
                        p,
                        Math.min(9, (int) (e[0] / max * 10))
                );
            }
        });
    }

    public static void hurt(Minecraft mc, Entity target, float amount) {
        var srv = mc.getSingleplayerServer();

        if (srv == null || mc.player == null || amount <= 0) {
            return;
        }

        UUID pid = mc.player.getUUID();
        int id = target.getId();

        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);

            if (sp == null) return;

            ServerLevel lvl = (ServerLevel) sp.level();
            Entity e = lvl.getEntity(id);

            if (e instanceof LivingEntity le && le.isAlive()) {
                le.invulnerableTime = 0;

                le.hurtServer(
                        lvl,
                        le == sp
                                ? lvl.damageSources().generic()
                                : lvl.damageSources().playerAttack(sp),
                        amount
                );
            }
        });
    }

    public static void explode(
            Minecraft mc,
            Vec3 center,
            double radiusBlocks,
            double csDamage
    ) {
        var srv = mc.getSingleplayerServer();

        if (srv == null || mc.player == null) {
            return;
        }

        UUID pid = mc.player.getUUID();
        double scale = CS16Config.get().damageScale;

        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);

            if (sp == null) return;

            ServerLevel lvl = (ServerLevel) sp.level();

            List<LivingEntity> list =
                    lvl.getEntitiesOfClass(
                            LivingEntity.class,
                            new AABB(center, center).inflate(radiusBlocks)
                    );

            for (LivingEntity le : list) {
                Vec3 t = le.getBoundingBox().getCenter();
                double d = t.distanceTo(center);

                if (d > radiusBlocks || !le.isAlive()) {
                    continue;
                }

                BlockHitResult r = lvl.clip(
                        new ClipContext(
                                center,
                                t,
                                ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE,
                                le
                        )
                );

                if (r.getType() != HitResult.Type.MISS
                        && r.getLocation().distanceTo(t) > 0.9) {
                    continue;
                }

                float hp = (float) (
                        csDamage
                                * (1.0 - d / radiusBlocks)
                                * scale
                );

                if (hp <= 0) continue;

                le.invulnerableTime = 0;

                le.hurtServer(
                        lvl,
                        le == sp
                                ? lvl.damageSources().generic()
                                : lvl.damageSources().playerAttack(sp),
                        hp
                );
            }

            if (CS16Config.get().grenadeCrater) {
                carveCrater(
                        lvl,
                        center,
                        CS16Config.get().craterRadius
                );
            }
        });
    }

    private static void carveCrater(
            ServerLevel lvl,
            Vec3 center,
            double radius
    ) {
        Random rnd = new Random();

        int ir = (int) Math.ceil(radius);
        BlockPos c = BlockPos.containing(center);

        for (int dx = -ir; dx <= ir; dx++) {
            for (int dy = -ir; dy <= ir; dy++) {
                for (int dz = -ir; dz <= ir; dz++) {

                    double d = Math.sqrt(
                            dx * dx +
                            dy * dy +
                            dz * dz
                    );

                    if (d > radius) continue;

                    if (d > radius * 0.7
                            && rnd.nextDouble()
                            < (d - radius * 0.7) / (radius * 0.3)) {
                        continue;
                    }

                    BlockPos bp = c.offset(dx, dy, dz);
                    BlockState st = lvl.getBlockState(bp);

                    if (st.isAir()) continue;

                    float hardness = st.getDestroySpeed(lvl, bp);

                    if (hardness < 0 || hardness > 25) continue;

                    lvl.removeBlock(bp, false);
                }
            }
        }
    }
}