package com.example.cs16minecraft.client;

import com.example.cs16minecraft.audio.CS16SoundManager;
import com.example.cs16minecraft.config.CS16Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CS 1.6 flashlight (F). Battery works like the original (drains 1% per 1.2 s = 120 s total, recharges 1% per 0.2 s)
 * and the HUD uses flash_empty / flash_full / flash_beam from the user's hud.txt.
 *
 * Minecraft has no dynamic lights, so the beam is made of two invisible vanilla "light" blocks placed in the air where
 * you are aiming (one a couple of blocks ahead, one at the end of the beam). They follow your aim and are removed when
 * you switch off, die, or run out of battery. Needs the integrated server (singleplayer / LAN host).
 */
public final class CS16Flashlight {
    private static boolean on;
    private static double battery = 1.0;
    private static double nextUpdate;
    private static BlockPos lastNear, lastFar;
    /** Light blocks we placed. Touched only on the server thread. */
    private static final List<BlockPos> LIGHTS = new ArrayList<>();

    private CS16Flashlight() {}

    public static boolean isOn() { return on; }

    public static double battery() { return battery; }

    private static double now() { return System.nanoTime() / 1.0e9; }

    private static void click() {
        CS16SoundManager.INSTANCE.playFirst(CS16Config.get().soundVolume, "items/flashlight1.wav");
    }

    public static void toggle() {
        if (!on && battery <= 0.02) return;
        on = !on;
        click();
        lastNear = lastFar = null;
        nextUpdate = 0;
    }

    /** Called once per rendered frame. */
    public static void update(Minecraft mc, LocalPlayer p, double dt) {
        boolean dead = p.isDeadOrDying();
        if (dead && on) { on = false; }
        if (on) {
            battery -= dt / 120.0;
            if (battery <= 0) { battery = 0; on = false; click(); }
        } else {
            battery = Math.min(1.0, battery + dt / 20.0);
        }

        if (!on) {
            if (lastNear != null || lastFar != null) { lastNear = lastFar = null; clearLights(mc); }
            return;
        }
        double t = now();
        if (t < nextUpdate) return;
        nextUpdate = t + 0.08;

        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F);
        double reach = 28.0;
        BlockHitResult r = p.level().clip(new ClipContext(eye, eye.add(look.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 end = r.getType() == HitResult.Type.MISS ? eye.add(look.scale(reach)) : r.getLocation().subtract(look.scale(0.6));
        BlockPos far = BlockPos.containing(end);
        BlockPos near = BlockPos.containing(eye.add(look.scale(Math.min(2.5, eye.distanceTo(end) * 0.5))));
        if (near.equals(far)) near = null;
        if (far.equals(lastFar) && (near == null ? lastNear == null : near.equals(lastNear))) return;
        lastFar = far;
        lastNear = near;
        placeLights(mc, near, far);
    }

    private static void placeLights(Minecraft mc, BlockPos near, BlockPos far) {
        var srv = mc.getSingleplayerServer();
        if (srv == null || mc.player == null) return;
        UUID pid = mc.player.getUUID();
        BlockPos n = near == null ? null : near.immutable(), f = far.immutable();
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);
            if (sp == null) return;
            ServerLevel lvl = (ServerLevel) sp.level();
            removeAll(lvl);
            put(lvl, n, 10);
            put(lvl, f, 15);
        });
    }

    private static void put(ServerLevel lvl, BlockPos bp, int level) {
        if (bp == null || !lvl.getBlockState(bp).isAir()) return;
        lvl.setBlock(bp, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level), 3);
        LIGHTS.add(bp);
    }

    private static void removeAll(ServerLevel lvl) {
        for (BlockPos q : LIGHTS) {
            if (lvl.getBlockState(q).is(Blocks.LIGHT)) lvl.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
        }
        LIGHTS.clear();
    }

    private static void clearLights(Minecraft mc) {
        var srv = mc.getSingleplayerServer();
        if (srv == null || mc.player == null) return;
        UUID pid = mc.player.getUUID();
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(pid);
            if (sp != null) removeAll((ServerLevel) sp.level());
        });
    }
}
