package com.example.cs16minecraft.weapon;

import com.example.cs16minecraft.config.CS16Config;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BellBlock;
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

/**
 * Applies damage on the server thread. The mod is client-side, so damage works in singleplayer / LAN host
 * (the integrated server lives in the same process). Dedicated servers would need a server component.
 */
public final class CS16Damage {
    private CS16Damage() {}

    private static final Map<Integer, long[]> PANIC = new HashMap<>();    // id -> {endMs, nextRepathMs}
    private static final Map<Integer, double[]> LOOK = new HashMap<>();   // id -> {endMs, x, y, z}
    private static final Map<Integer, long[]> CONFUSED = new HashMap<>(); // id -> {endMs, nextStumbleMs}
    private static final Random RNG = new Random();

    // vanilla speed modifiers: villagers' alerted walk, cats' sprint, monsters' normal chase speed
    private static final double VILLAGER_FLEE = 0.75, CAT_FLEE = 1.33, MONSTER_CHASE = 1.0, STUMBLE = 0.6;

    private static boolean isVillager(Mob m) {
        String k = m.getType().getDescriptionId();
        return k.equals("entity.minecraft.villager") || k.equals("entity.minecraft.wandering_trader");
    }

    private static boolean isCat(Mob m) { return m.getType().getDescriptionId().equals("entity.minecraft.cat"); }

    /**
     * A loud noise at pos. Hostile mobs walk to it at their normal speed (and target the player when close);
     * villagers and cats flee from the player with real pathfinding for 5 seconds.
     */
    public static void gunshot(Minecraft mc, Vec3 pos, double radius) {
        var srv = mc.getSingleplayerServer();
        if (!CS16Config.get().mobAlerts || srv == null || mc.player == null || radius < 2) return;
        UUID pid = mc.player.getUUID();
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);
            if (sp == null) return;
            ServerLevel lvl = (ServerLevel) sp.level();
            long now = System.currentTimeMillis();
            for (Mob mob : lvl.getEntitiesOfClass(Mob.class, new AABB(pos, pos).inflate(radius))) {
                double d = mob.position().distanceTo(pos);
                if (d > radius) continue;
                if (isVillager(mob) || isCat(mob)) {
                    LOOK.remove(mob.getId());
                    PANIC.put(mob.getId(), new long[]{now + 5000, 0});
                } else if (mob instanceof Monster) {
                    alertMonster(mob, sp, pos, d, radius);
                }
            }
        });
    }

    private static void alertMonster(Mob mob, ServerPlayer sp, Vec3 pos, double d, double radius) {
        if (CONFUSED.containsKey(mob.getId())) return; // flashed mobs can't pick up the sound
        if (d <= radius * 0.6) mob.setTarget(sp);
        else mob.getNavigation().moveTo(pos.x, pos.y, pos.z, MONSTER_CHASE);
    }

    /** A shot bell rings (real bell sound + vanilla bell effects); villagers look at it, cats freak out, hostiles come. */
    public static void ringBell(Minecraft mc, BlockPos bell, Direction face) {
        var srv = mc.getSingleplayerServer();
        if (srv == null || mc.player == null) return;
        UUID pid = mc.player.getUUID();
        BlockPos pos = bell.immutable();
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);
            if (sp == null) return;
            ServerLevel lvl = (ServerLevel) sp.level();
            if (lvl.getBlockState(pos).getBlock() instanceof BellBlock bellBlock) bellBlock.attemptToRing(lvl, pos, face); // instance method in 26.2
            long now = System.currentTimeMillis();
            Vec3 c = Vec3.atCenterOf(pos);
            double radius = 32;
            for (Mob mob : lvl.getEntitiesOfClass(Mob.class, new AABB(c, c).inflate(radius))) {
                double d = mob.position().distanceTo(c);
                if (d > radius) continue;
                if (isVillager(mob)) {
                    mob.getNavigation().stop();
                    LOOK.put(mob.getId(), new double[]{now + 4000, c.x, c.y, c.z});
                } else if (isCat(mob)) {
                    PANIC.put(mob.getId(), new long[]{now + 5000, 0});
                } else if (mob instanceof Monster) {
                    alertMonster(mob, sp, c, d, radius);
                }
            }
        });
    }

    /** Flashbang: hostile mobs that can see it lose their target and stumble around for a few seconds. */
    public static void flashMobs(Minecraft mc, Vec3 pos, double radius) {
        var srv = mc.getSingleplayerServer();
        if (srv == null || mc.player == null) return;
        UUID pid = mc.player.getUUID();
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);
            if (sp == null) return;
            ServerLevel lvl = (ServerLevel) sp.level();
            long now = System.currentTimeMillis();
            for (Mob mob : lvl.getEntitiesOfClass(Mob.class, new AABB(pos, pos).inflate(radius))) {
                if (!(mob instanceof Monster)) continue;
                double d = mob.position().distanceTo(pos);
                if (d > radius) continue;
                BlockHitResult r = lvl.clip(new ClipContext(pos, mob.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
                if (r.getType() != HitResult.Type.MISS && r.getLocation().distanceTo(mob.getEyePosition()) > 0.9) continue; // no line of sight
                long dur = (long) (3000 + 4000 * (1.0 - d / radius)); // 3-7 s, longer when close
                mob.setTarget(null);
                CONFUSED.put(mob.getId(), new long[]{now + dur, 0});
            }
        });
    }

    /**
     * Drives all timed mob behaviours on the server thread (call every client tick) and keeps the owner's
     * server-side player silent, so no vanilla hurt / fall sounds are broadcast to them.
     */
    public static void tickPanic(Minecraft mc) {
        var srv = mc.getSingleplayerServer();
        if (srv == null || mc.player == null) return;
        UUID pid = mc.player.getUUID();
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);
            if (sp == null) return;
            sp.setSilent(true);
            if (PANIC.isEmpty() && LOOK.isEmpty() && CONFUSED.isEmpty()) return;
            ServerLevel lvl = (ServerLevel) sp.level();
            long now = System.currentTimeMillis();

            var it = PANIC.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                Entity ent = lvl.getEntity(e.getKey());
                long[] v = e.getValue();
                if (!(ent instanceof Mob mob) || !mob.isAlive() || now >= v[0]) {
                    if (ent instanceof Mob m2) m2.getNavigation().stop();
                    it.remove();
                    continue;
                }
                if (now >= v[1] || mob.getNavigation().isDone()) {
                    v[1] = now + 800 + RNG.nextInt(700);
                    flee(mob, sp.position(), isCat(mob) ? CAT_FLEE : VILLAGER_FLEE);
                }
            }

            var li = LOOK.entrySet().iterator();
            while (li.hasNext()) {
                var e = li.next();
                Entity ent = lvl.getEntity(e.getKey());
                double[] v = e.getValue();
                if (!(ent instanceof Mob mob) || !mob.isAlive() || now >= (long) v[0]) { li.remove(); continue; }
                mob.getLookControl().setLookAt(v[1], v[2], v[3]);
            }

            var ci = CONFUSED.entrySet().iterator();
            while (ci.hasNext()) {
                var e = ci.next();
                Entity ent = lvl.getEntity(e.getKey());
                long[] v = e.getValue();
                if (!(ent instanceof Mob mob) || !mob.isAlive() || now >= v[0]) { ci.remove(); continue; }
                mob.setTarget(null); // blinded: can't keep (or find) a target
                if (now >= v[1]) {
                    v[1] = now + 900 + RNG.nextInt(900);
                    mob.getNavigation().moveTo(mob.getX() + (RNG.nextDouble() - 0.5) * 8, mob.getY(), mob.getZ() + (RNG.nextDouble() - 0.5) * 8, STUMBLE);
                }
            }
        });
    }

    /** Pathfinds away from a point at the given speed modifier (tries a few headings until one has a route). */
    private static void flee(Mob mob, Vec3 from, double speed) {
        Vec3 away = new Vec3(mob.getX() - from.x, 0, mob.getZ() - from.z);
        if (away.lengthSqr() < 0.01) away = new Vec3(RNG.nextDouble() - 0.5, 0, RNG.nextDouble() - 0.5);
        away = away.normalize();
        for (int attempt = 0; attempt < 5; attempt++) {
            double ang = attempt == 0 ? 0 : (RNG.nextDouble() - 0.5) * 1.6 * attempt / 2.0;
            double c = Math.cos(ang), s = Math.sin(ang);
            double dx = away.x * c - away.z * s, dz = away.x * s + away.z * c;
            double dist = 10 + RNG.nextDouble() * 5;
            if (mob.getNavigation().moveTo(mob.getX() + dx * dist, mob.getY(), mob.getZ() + dz * dist, speed)) return;
        }
    }

    /** Accumulated bullet damage per block, only touched on the server thread. {damage, lastHitMillis} */
    private static final Map<Long, double[]> BLOCK_HP = new HashMap<>();

    /** Damages the block a bullet hit. Block hp scales with hardness, so each weapon chews through blocks differently. */
    public static void damageBlock(Minecraft mc, BlockPos pos, double csDamage) {
        CS16Config cfg = CS16Config.get();
        var srv = mc.getSingleplayerServer();
        if (!cfg.blockDamage || srv == null || mc.player == null || csDamage <= 0) return;
        UUID pid = mc.player.getUUID();
        BlockPos p = pos.immutable();
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);
            if (sp == null) return;
            ServerLevel lvl = (ServerLevel) sp.level();
            BlockState st = lvl.getBlockState(p);
            float hardness = st.getDestroySpeed(lvl, p);
            if (st.isAir() || hardness < 0 || !st.getFluidState().isEmpty()) return; // bedrock, air, water
            long now = System.currentTimeMillis();
            BLOCK_HP.values().removeIf(v -> now - (long) v[1] > 15000);               // damage fades if you stop shooting
            double[] e = BLOCK_HP.computeIfAbsent(p.asLong(), k -> new double[]{0, now});
            e[0] += csDamage;
            e[1] = now;
            double max = Math.max(5.0, hardness * cfg.blockHpPerHardness);
            int breaker = p.hashCode();
            if (e[0] >= max) {
                BLOCK_HP.remove(p.asLong());
                lvl.destroyBlockProgress(breaker, p, -1);
                lvl.destroyBlock(p, false, sp);
            } else {
                lvl.destroyBlockProgress(breaker, p, Math.min(9, (int) (e[0] / max * 10)));
            }
        });
    }

    /** @param amount Minecraft health points (already scaled). */
    public static void hurt(Minecraft mc, Entity target, float amount) {
        var srv = mc.getSingleplayerServer(); // type inferred: its package moved in 26.x
        if (srv == null || mc.player == null || amount <= 0) return;
        UUID pid = mc.player.getUUID();
        int id = target.getId();
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);
            if (sp == null) return;
            ServerLevel lvl = (ServerLevel) sp.level();
            Entity e = lvl.getEntity(id);
            if (e instanceof LivingEntity le && le.isAlive()) {
                le.invulnerableTime = 0; // allow full-auto fire to land every bullet
                le.hurtServer(lvl, le == sp ? lvl.damageSources().generic() : lvl.damageSources().playerAttack(sp), amount);
            }
        });
    }

    /** Radial explosion damage with linear falloff and line-of-sight check. csDamage is out of 100. */
    public static void explode(Minecraft mc, Vec3 center, double radiusBlocks, double csDamage) {
        explode(mc, center, radiusBlocks, csDamage, CS16Config.get().craterRadius);
    }

    public static void explode(Minecraft mc, Vec3 center, double radiusBlocks, double csDamage, double crater) {
        var srv = mc.getSingleplayerServer(); // type inferred: its package moved in 26.x
        if (srv == null || mc.player == null) return;
        UUID pid = mc.player.getUUID();
        double scale = CS16Config.get().damageScale;
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);
            if (sp == null) return;
            ServerLevel lvl = (ServerLevel) sp.level();
            List<LivingEntity> list = lvl.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radiusBlocks));
            for (LivingEntity le : list) {
                Vec3 t = le.getBoundingBox().getCenter();
                double d = t.distanceTo(center);
                if (d > radiusBlocks || !le.isAlive()) continue;
                BlockHitResult r = lvl.clip(new ClipContext(center, t, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, le));
                if (r.getType() != HitResult.Type.MISS && r.getLocation().distanceTo(t) > 0.9) continue; // behind cover
                float hp = (float) (csDamage * (1.0 - d / radiusBlocks) * scale);
                if (hp <= 0) continue;
                le.invulnerableTime = 0;
                le.hurtServer(lvl, le == sp ? lvl.damageSources().generic() : lvl.damageSources().playerAttack(sp), hp);
            }
            if (CS16Config.get().grenadeCrater) carveCrater(lvl, center, crater);
        });
    }

    /** Removes a ragged sphere of blocks. Bedrock and very hard blocks (obsidian etc.) survive. Server thread only. */
    private static void carveCrater(ServerLevel lvl, Vec3 center, double radius) {
        Random rnd = new Random();
        int ir = (int) Math.ceil(radius);
        BlockPos c = BlockPos.containing(center);
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dy = -ir; dy <= ir; dy++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > radius) continue;
                    if (d > radius * 0.7 && rnd.nextDouble() < (d - radius * 0.7) / (radius * 0.3)) continue; // ragged rim
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
