package com.example.cs16minecraft.hud;

import com.example.cs16minecraft.config.CS16Config;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** CS-style four-line crosshair; the gap is the real spread cone projected to the screen, so it matches where bullets can land. */
public final class CS16Crosshair {
    private static double smoothGap;

    private CS16Crosshair() {}

    /**
     * @param inaccuracyDeg current spread half-angle
     * @param verticalFovDeg current vertical FOV
     * @param u gui units per HUD pixel
     */
    public static void draw(GuiGraphicsExtractor g, int sw, int sh, double inaccuracyDeg, double verticalFovDeg, double u, double dt) {
        CS16Config.Crosshair c = CS16Config.get().crosshair;
        double focalGui = (sh / 2.0) / Math.tan(Math.toRadians(verticalFovDeg) / 2.0);
        double target = c.gap * u;
        if (c.dynamic) target += Math.tan(Math.toRadians(inaccuracyDeg)) * focalGui;
        smoothGap += (target - smoothGap) * Math.min(1.0, dt * 16.0);

        int cx = sw / 2, cy = sh / 2;
        int gap = Math.max(0, (int) Math.round(smoothGap));
        int len = Math.max(1, (int) Math.round(c.size * u));
        int th = Math.max(1, (int) Math.round(c.thickness * u));
        int half = th / 2;
        int a = Math.max(0, Math.min(255, c.alpha));
        int color = (a << 24) | (clamp(c.red) << 16) | (clamp(c.green) << 8) | clamp(c.blue);
        int shade = a << 24;

        int[][] bars = {
                {cx - half, cy - gap - len, cx - half + th, cy - gap},
                {cx - half, cy + gap, cx - half + th, cy + gap + len},
                {cx - gap - len, cy - half, cx - gap, cy - half + th},
                {cx + gap, cy - half, cx + gap + len, cy - half + th}
        };
        if (c.outline) for (int[] b : bars) g.fill(b[0] - 1, b[1] - 1, b[2] + 1, b[3] + 1, shade);
        for (int[] b : bars) g.fill(b[0], b[1], b[2], b[3], color);
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
