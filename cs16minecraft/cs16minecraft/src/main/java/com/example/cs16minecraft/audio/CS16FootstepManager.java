package com.example.cs16minecraft.audio;

import com.example.cs16minecraft.client.CS16Client;
import com.example.cs16minecraft.config.CS16Config;
import com.example.cs16minecraft.movement.GoldSrcMovementController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SoundType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CS-style footsteps, jump and landing sounds from the player/pl_* files in the user's install.
 * Steps are distance-based, silent when walking (shift) or crouched like CS, and use the surface under the player.
 */
public final class CS16FootstepManager {
    public static final CS16FootstepManager INSTANCE = new CS16FootstepManager();

    private static final double STEP_UNITS = 72.0;      // distance between steps while running
    private static final double AUDIBLE_SPEED = 135.0;  // slower than this (shift-walk) is silent

    private double acc;
    private boolean wasGrounded = true;
    private double fallSpeed;
    private int lastIdx = -1;

    private CS16FootstepManager() {}

    public void tick(Minecraft mc, LocalPlayer p, double dt) {
        if (p.isDeadOrDying() || !CS16Config.get().movementEnabled) return;
        GoldSrcMovementController c = CS16Client.MOVEMENT.controller;
        boolean grounded = c.grounded;
        double speed = c.horizontalSpeed();
        double vol = CS16Config.get().soundVolume;

        if (!grounded) fallSpeed = Math.min(fallSpeed, c.vy);
        if (grounded && !wasGrounded) {
            if (fallSpeed < -420) {                         // a fall that hurts: CS fall-pain sound instead of Minecraft's
                if (!CS16SoundManager.INSTANCE.playAnyOf(vol, "player/pl_fallpain1.wav", "player/pl_fallpain2.wav", "player/pl_fallpain3.wav"))
                    CS16SoundManager.INSTANCE.playRandomGroup(vol, "sound/player/pl_fallpain", "sound/player/pl_pain");
                step(p, vol * 0.9);
            } else if (fallSpeed < -90) step(p, vol * 0.9); // normal landing
            acc = STEP_UNITS * 0.5;
            fallSpeed = 0;
        } else if (!grounded && wasGrounded && c.vy > 100) {
            step(p, vol * 0.6);                             // jump
        }
        wasGrounded = grounded;

        if (grounded && !c.ducked && speed >= AUDIBLE_SPEED && mc.mouseHandler.isMouseGrabbed()) {
            acc += speed * dt;
            if (acc >= STEP_UNITS) { acc = 0; step(p, vol * 0.65); }
        } else if (speed < AUDIBLE_SPEED) {
            acc = Math.min(acc, STEP_UNITS * 0.5);
        }
    }

    private void step(LocalPlayer p, double volume) {
        String[] prefixes = prefixesFor(p);
        for (String prefix : prefixes) {
            List<String> files = CS16Client.ASSETS.listFiles(prefix);
            files.removeIf(f -> !f.endsWith(".wav"));
            if (files.isEmpty()) continue;
            int idx;
            do { idx = ThreadLocalRandom.current().nextInt(files.size()); } while (files.size() > 1 && idx == lastIdx);
            lastIdx = idx;
            CS16SoundManager.INSTANCE.play(files.get(idx), volume);
            return;
        }
    }

    private static String[] prefixesFor(LocalPlayer p) {
        if (p.isInWater()) return new String[]{"sound/player/pl_slosh", "sound/player/pl_step"};
        SoundType st = p.level().getBlockState(BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ())).getSoundType();
        if (st == SoundType.GRASS || st == SoundType.GRAVEL || st == SoundType.SAND) return new String[]{"sound/player/pl_dirt", "sound/player/pl_step"};
        if (st == SoundType.SNOW) return new String[]{"sound/player/pl_snow", "sound/player/pl_dirt", "sound/player/pl_step"};
        if (st == SoundType.METAL) return new String[]{"sound/player/pl_metal", "sound/player/pl_step"};
        if (st == SoundType.GLASS) return new String[]{"sound/player/pl_tile", "sound/player/pl_step"};
        if (st == SoundType.WOOD) return new String[]{"sound/player/pl_wood", "sound/player/pl_duct", "sound/player/pl_step"};
        return new String[]{"sound/player/pl_step"};
    }
}
