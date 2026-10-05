package com.example.cs16minecraft.client;

import com.example.cs16minecraft.audio.CS16SoundManager;
import com.example.cs16minecraft.config.CS16Config;
import com.example.cs16minecraft.hud.CS16DeathScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;

/** Detects the local player dying, plays the CS death sound once, and swaps in the CS death screen. */
public final class CS16Death {
    private static boolean wasDead;
    private static long deathNanos;
    private static float rollSign = 1f;

    private CS16Death() {}

    /** Camera roll (radians) while dead: eases in over ~0.5s to about 25 degrees, 0 when alive. */
    public static float rollRadians() {
        if (!wasDead) return 0f;
        double t = (System.nanoTime() - deathNanos) / 1.0e9;
        double ease = Math.min(1.0, t / 0.5);
        ease = 1.0 - (1.0 - ease) * (1.0 - ease);
        return (float) Math.toRadians(25.0 * ease) * rollSign;
    }

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) {
            wasDead = false;
            return;
        }
        boolean dead = p.isDeadOrDying();
        if (dead && !wasDead) {
            deathNanos = System.nanoTime();
            rollSign = Math.random() < 0.5 ? -1f : 1f; // fall onto either side
            // die1-3 are the CS player death screams; fall back to the other death sets if they are absent
            CS16SoundManager.INSTANCE.playRandomGroup(CS16Config.get().soundVolume,
                    "sound/player/die", "sound/player/death", "sound/player/pl_die");
        }
        wasDead = dead;

        Screen s = mc.gui.screen();
        if (dead && s instanceof DeathScreen) {
            com.example.cs16minecraft.CS16Minecraft.LOGGER.info("CS16: replaced vanilla death screen");
            mc.gui.setScreen(new CS16DeathScreen());
        } else if (!dead && s instanceof CS16DeathScreen) {
            mc.gui.setScreen(null);
        }
    }
}
