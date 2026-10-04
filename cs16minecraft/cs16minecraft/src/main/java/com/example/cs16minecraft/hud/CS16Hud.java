package com.example.cs16minecraft.hud;

import com.example.cs16minecraft.CS16Minecraft;
import com.example.cs16minecraft.assets.CS16AssetManager;
import com.example.cs16minecraft.audio.CS16FootstepManager;
import com.example.cs16minecraft.client.CS16Client;
import com.example.cs16minecraft.client.CS16PlayerState;
import com.example.cs16minecraft.config.CS16Config;
import com.example.cs16minecraft.render.CS16WeaponRenderer;
import com.example.cs16minecraft.render.TextureBridge;
import com.example.cs16minecraft.weapon.CS16GrenadeEntity;
import com.example.cs16minecraft.weapon.CS16Weapon;
import com.example.cs16minecraft.weapon.CS16WeaponManager;
import com.example.cs16minecraft.weapon.CS16WeaponRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * CS 1.6 HUD drawn from the user's own hud.txt / weapon_*.txt sprite lists, laid out with the same formulas
 * as the original client (health bottom-left, armor at 1/5 width, ammo bottom-right, weapon menu top-left).
 * Sprites are drawn at their native pixel size (times hudScale), like GoldSrc does.
 */
public final class CS16Hud {
    private static final int DHN_DRAWZERO = 1, DHN_3DIGITS = 2;
    private static final CS16WeaponRenderer VIEWMODEL = new CS16WeaponRenderer();

    private static long lastNanos = System.nanoTime();
    private static double u;                    // gui units per HUD pixel
    private static double fadeHealth, fadeAmmo;
    private static int lastHealth = -1, lastAmmo = -1;

    private CS16Hud() {}

    public static void register() {
        List<Identifier> vanilla = List.of(
                VanillaHudElements.HOTBAR, VanillaHudElements.CROSSHAIR, VanillaHudElements.HEALTH_BAR,
                VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR,
                VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR,
                VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP,
                VanillaHudElements.CHAT); // chat overlay hidden; the chat/command box still opens with T or /
        for (Identifier id : vanilla) {
            HudElementRegistry.replaceElement(id, original -> (HudElement) (g, dt) -> { });
        }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(CS16Minecraft.MOD_ID, "cs16_hud"), CS16Hud::extract);
    }

    private static void extract(GuiGraphicsExtractor g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        long n = System.nanoTime();
        double frameDt = Math.min(0.1, (n - lastNanos) / 1.0e9);
        lastNanos = n;
        if (p == null) return;

        CS16AssetManager a = CS16Client.ASSETS;
        boolean ready = a.state() == CS16AssetManager.State.READY;
        CS16WeaponManager wm = CS16WeaponManager.INSTANCE;
        if (ready) {
            wm.update(mc, p, frameDt);
            CS16FootstepManager.INSTANCE.tick(mc, p, frameDt);
        }
        if (mc.gui.hud.isHidden()) return;

        int sw = g.guiWidth(), sh = g.guiHeight();
        if (a.state() == CS16AssetManager.State.MISSING_INSTALL || a.state() == CS16AssetManager.State.ERROR) {
            drawError(g, mc, a, sw, sh);
            return;
        }
        double now = CS16WeaponManager.now();
        CS16Config cfg = CS16Config.get();
        double guiScale = mc.getWindow().getGuiScale();
        u = cfg.hudScale / guiScale;
        double vfov = mc.options.fov().get();
        float partial = dt.getGameTimeDeltaPartialTick(false);

        if (ready && !p.isDeadOrDying()) {
            VIEWMODEL.render(g, sw, sh, frameDt, p, partial, guiScale);
            float smoke = wm.grenades.smokeAlpha(p);
            if (smoke > 0) g.fill(0, 0, sw, sh, ((int) (smoke * 255) << 24) | 0x707A70);
            if (wm.scope() > 0) drawScope(g, sw, sh);
            else if (wm.current.isGun()) CS16Crosshair.draw(g, sw, sh, wm.inaccuracy, vfov, u, frameDt);
            drawStatus(g, mc, sw, sh, frameDt, p);
            drawAmmo(g, wm, sw, sh, frameDt);
            if (now < wm.menuUntil) drawWeaponMenu(g, mc, wm);
            CS16RadioMenu.draw(g, mc, sw, sh);
        }
        float flash = wm.grenades.flashAlpha(now);
        if (flash > 0) g.fill(0, 0, sw, sh, ((int) (flash * 255) << 24) | 0xFFFFFF);
        if (cfg.debug) CS16Debug.draw(g, mc);
    }

    // ------------------------------------------------------------------ sprite helpers (HUD pixel space)

    private static int hudColor(int r, int gr, int b, int a) {
        return 0xFF000000 | ((r * a / 255) << 16) | ((gr * a / 255) << 8) | (b * a / 255);
    }

    private static int hudAlpha(double fade) {
        int min = CS16Config.get().hudMinAlpha;
        return Math.min(255, min + (int) (fade * (255 - min)));
    }

    private static int sprite(GuiGraphicsExtractor g, String name, double x, double y, int color) {
        TextureBridge.Tex t = TextureBridge.hud(name);
        if (t == null) return 0;
        TextureBridge.draw(g, t, (int) Math.round(x * u), (int) Math.round(y * u),
                Math.max(1, (int) Math.round(t.w() * u)), Math.max(1, (int) Math.round(t.h() * u)), color);
        return t.w();
    }

    private static int digitW() { var t = TextureBridge.hud("number_0"); return t == null ? 20 : t.w(); }

    private static int digitH() { var t = TextureBridge.hud("number_0"); return t == null ? 24 : t.h(); }

    /** Port of the original DrawHudNumber: fixed-width padding for 3 digits, optional zero. */
    private static double number(GuiGraphicsExtractor g, int value, double x, double y, int flags, int color) {
        int w = digitW();
        int n = Math.max(0, Math.min(999, value));
        if ((flags & DHN_3DIGITS) != 0) {
            if (n >= 100) { sprite(g, "number_" + (n / 100), x, y, color); x += w; } else x += w;
            if (n >= 10) { sprite(g, "number_" + ((n % 100) / 10), x, y, color); x += w; } else x += w;
        } else {
            if (n >= 100) { sprite(g, "number_" + (n / 100), x, y, color); x += w; }
            if (n >= 10) { sprite(g, "number_" + ((n % 100) / 10), x, y, color); x += w; }
        }
        if (n > 0 || (flags & DHN_DRAWZERO) != 0) {
            sprite(g, "number_" + (n % 10), x, y, color);
            x += w;
        }
        return x;
    }

    private static void bar(GuiGraphicsExtractor g, double x, double y, double w, double h, int color) {
        int x1 = (int) Math.round(x * u), y1 = (int) Math.round(y * u);
        g.fill(x1, y1, x1 + Math.max(1, (int) Math.round(w * u)), y1 + Math.max(1, (int) Math.round(h * u)), color);
    }

    // ------------------------------------------------------------------ HUD blocks

    private static void drawStatus(GuiGraphicsExtractor g, Minecraft mc, int sw, int sh, double dt, LocalPlayer p) {
        double hudH = sh / u, hudW = sw / u;
        int fh = digitH(), hw = digitW();
        int health = Math.max(0, Math.round(p.getHealth() / p.getMaxHealth() * 100f));
        if (health != lastHealth) { if (lastHealth >= 0) fadeHealth = 1; lastHealth = health; }
        fadeHealth = Math.max(0, fadeHealth - dt * 0.7);
        int a = hudAlpha(fadeHealth);
        int hpColor = health > 25 ? hudColor(255, 160, 0, a) : hudColor(250, 0, 0, Math.max(a, 160));

        double y = hudH - fh - fh / 2.0;
        TextureBridge.Tex cross = TextureBridge.hud("cross");
        int crossW = cross != null ? cross.w() : 0;
        if (cross != null) sprite(g, "cross", crossW / 2.0, y, hpColor);
        double x = crossW + hw / 2.0;
        x = number(g, health, x, y, DHN_3DIGITS | DHN_DRAWZERO, hpColor);
        x += hw / 2.0;
        bar(g, x, y, Math.max(1, hw / 10.0), fh, hpColor);

        // armor at 1/5 of the screen width
        int armor = CS16PlayerState.armor;
        int ac = hudColor(255, 160, 0, hudAlpha(0));
        double ax = hudW / 5.0;
        TextureBridge.Tex empty = TextureBridge.hud("suit_empty"), full = TextureBridge.hud("suit_full");
        if (empty != null) {
            sprite(g, "suit_empty", ax, y, ac);
            if (armor > 0 && full != null) {
                int skip = (int) (full.h() * (100 - Math.min(100, armor)) * 0.01);
                TextureBridge.drawPart(g, full, (int) Math.round(ax * u), (int) Math.round((y + skip) * u),
                        Math.max(1, (int) Math.round(full.w() * u)), Math.max(1, (int) Math.round((full.h() - skip) * u)),
                        skip, full.h() - skip, ac);
            }
            ax += empty.w();
        }
        number(g, armor, ax, y, DHN_3DIGITS | DHN_DRAWZERO, ac);

        // money, right side
        int money = CS16PlayerState.money;
        String ms = Integer.toString(money);
        int dollarW = TextureBridge.hud("dollar") != null ? TextureBridge.hud("dollar").w() : 0;
        double mx = hudW - 16 - dollarW - ms.length() * hw;
        double my = hudH - fh * 4.5;
        mx += sprite(g, "dollar", mx, my, ac);
        for (char ch : ms.toCharArray()) mx += sprite(g, "number_" + ch, mx, my, ac);
    }

    private static void drawAmmo(GuiGraphicsExtractor g, CS16WeaponManager wm, int sw, int sh, double dt) {
        CS16Weapon w = wm.current;
        if (w.mode == CS16Weapon.Mode.MELEE) return;
        double hudH = sh / u, hudW = sw / u;
        int fh = digitH(), hw = digitW();
        int clip = wm.clip();
        if (clip != lastAmmo) { if (lastAmmo >= 0) fadeAmmo = 1; lastAmmo = clip; }
        fadeAmmo = Math.max(0, fadeAmmo - dt * 0.7);
        int col = clip == 0 && w.isGun() ? hudColor(250, 0, 0, 200) : hudColor(255, 160, 0, hudAlpha(fadeAmmo));
        double y = hudH - fh - fh / 2.0;

        TextureBridge.Tex icon = TextureBridge.hud(w.hudFile + "/ammo");
        int iconW = icon != null ? icon.w() : 0;
        double x;
        if (w.isGun()) {
            x = hudW - 8 * hw - iconW;
            x = number(g, clip, x, y, DHN_DRAWZERO | DHN_3DIGITS, col);
            x += hw / 2.0;
            bar(g, x, y, Math.max(1, hw / 10.0), fh, col);
            x += Math.max(1, hw / 10.0) + hw / 2.0;
            number(g, wm.reserve(), x, y, DHN_DRAWZERO | DHN_3DIGITS, col);
        } else { // grenades: just the count
            x = hudW - 3 * hw - iconW;
            number(g, CS16Config.get().infiniteAmmo ? 1 : clip, x, y, DHN_DRAWZERO, col);
        }
        if (icon != null) sprite(g, w.hudFile + "/ammo", hudW - iconW - hw, y - icon.h() / 8.0, col);
    }

    private static void drawWeaponMenu(GuiGraphicsExtractor g, Minecraft mc, CS16WeaponManager wm) {
        double x = 10;
        for (int slot = 1; slot <= 4; slot++) {
            List<CS16Weapon> list = CS16WeaponRegistry.slot(slot);
            double y = 8;
            int colW = 100;
            sprite(g, "number_" + slot, x, y, hudColor(255, 160, 0, 200));
            y += digitH() + 4;
            for (CS16Weapon w : list) {
                boolean sel = w == wm.current;
                TextureBridge.Tex t = TextureBridge.hud(w.hudFile + (sel ? "/weapon_s" : "/weapon"));
                int col = sel ? hudColor(255, 160, 0, 255) : hudColor(255, 160, 0, 110);
                if (t != null) {
                    sprite(g, w.hudFile + (sel ? "/weapon_s" : "/weapon"), x, y, col);
                    colW = Math.max(colW, t.w());
                    y += t.h() + 2;
                } else {
                    g.text(mc.font, w.name, (int) Math.round(x * u), (int) Math.round(y * u), col);
                    y += 12;
                }
            }
            x += colW + 8;
        }
    }

    private static void drawScope(GuiGraphicsExtractor g, int sw, int sh) {
        int black = 0xFF000000, cx = sw / 2, cy = sh / 2;
        int r = Math.min((int) (sh * 0.48), sw / 2);
        g.fill(0, 0, cx - r, sh, black);
        g.fill(cx + r, 0, sw, sh, black);
        for (int y = 0; y < sh; y += 2) {
            int dy = y - cy;
            if (Math.abs(dy) >= r) { g.fill(cx - r, y, cx + r, y + 2, black); continue; }
            int hw = (int) Math.sqrt((double) r * r - (double) dy * dy);
            g.fill(cx - r, y, cx - hw, y + 2, black);
            g.fill(cx + hw, y, cx + r, y + 2, black);
        }
        g.fill(cx - r, cy, cx + r, cy + 1, black);
        g.fill(cx, cy - r, cx + 1, cy + r, black);
    }

    private static void drawError(GuiGraphicsExtractor g, Minecraft mc, CS16AssetManager a, int sw, int sh) {
        String[] lines = {
                "CS 1.6 assets unavailable",
                a.status(),
                "This mod needs your own locally installed Half-Life with Counter-Strike 1.6.",
                "It does not include or download any game files."
        };
        int w = 0;
        for (String l : lines) w = Math.max(w, mc.font.width(l));
        int x = (sw - w) / 2, y = sh / 3;
        g.fill(x - 8, y - 8, x + w + 8, y + lines.length * 12 + 4, 0xCC200000);
        for (int i = 0; i < lines.length; i++) g.text(mc.font, lines[i], x, y + i * 12, i == 0 ? 0xFFFF5555 : 0xFFFFFFFF);
    }
}
