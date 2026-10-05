package com.example.cs16minecraft.hud;

import com.example.cs16minecraft.assets.CS16AssetManager;
import com.example.cs16minecraft.client.CS16Client;
import com.example.cs16minecraft.movement.GoldSrcMovementController;
import com.example.cs16minecraft.weapon.CS16WeaponManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Debug overlay: weapon + movement + asset pipeline status. Toggle with F8 or "debug" in the config. */
public final class CS16Debug {
    private CS16Debug() {}

    public static void draw(GuiGraphicsExtractor g, Minecraft mc) {
        CS16AssetManager a = CS16Client.ASSETS;
        GoldSrcMovementController m = CS16Client.MOVEMENT.controller;
        CS16WeaponManager w = CS16WeaponManager.INSTANCE;
        List<String> l = new ArrayList<>();
        l.add("CS16 DEBUG");
        l.add(String.format(Locale.ROOT, "weapon: %s  state: %s  ammo: %d / %d  scope: %d  spread: %.2f deg",
                w.current.id, w.state, w.clip(), w.reserve(), w.scope(), w.inaccuracy));
        l.add("model: " + (w.currentModel() != null ? w.current.model + " (" + w.currentModel().sequences.size() + " seq)" : "NOT LOADED")
                + "  seq: " + (w.currentSequence() != null ? w.currentSequence().label : "-"));
        if (w.currentModel() != null) {
            StringBuilder sb = new StringBuilder("bodyparts: ");
            for (var part : w.currentModel().bodyParts) {
                sb.append('[');
                for (var sm : part) sb.append(sm.name).append(' ');
                sb.append("] ");
            }
            l.add(sb.toString());
        }
        if (w.currentModel() != null) {
            StringBuilder sq = new StringBuilder("sequences: ");
            for (var q : w.currentModel().sequences) { if (sq.length() > 150) { sq.append("..."); break; } sq.append(q.label).append(", "); }
            l.add(sq.toString());
        }
        l.add(String.format(Locale.ROOT, "velocity u/s: %.1f  %.1f  %.1f", m.vx, m.vy, m.vz));
        l.add(String.format(Locale.ROOT, "horizontal: %.1f u/s   vertical: %.1f u/s", m.horizontalSpeed(), m.vy));
        l.add("grounded: " + m.grounded + "   crouching: " + m.ducked + "   state: " + m.state);
        l.add("install: " + (a.root() != null ? a.root() : "not found"));
        l.add("assets: " + a.state() + " - " + a.status());
        l.add("files: " + a.modelFileCount() + " mdl, " + a.spriteFileCount() + " spr, " + a.soundFileCount() + " wav");
        l.add("loaded: " + a.loadedModels() + " models, " + a.loadedHudSprites() + " hud sprites");
        int shown = Math.min(8, a.errors().size());
        l.add("conversion/sound errors: " + a.errors().size());
        for (int i = 0; i < shown; i++) l.add("  ! " + a.errors().get(i));

        int y = 4;
        for (String s : l) {
            g.text(mc.font, s, 4, y, 0xFFFFFF55);
            y += 10;
        }
    }
}
