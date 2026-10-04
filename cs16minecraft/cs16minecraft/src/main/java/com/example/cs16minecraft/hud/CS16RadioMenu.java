package com.example.cs16minecraft.hud;

import com.example.cs16minecraft.audio.CS16SoundManager;
import com.example.cs16minecraft.client.CS16Client;
import com.example.cs16minecraft.config.CS16Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * CS 1.6 radio / voice menu (G). Choose a category, then a command: the original radio voice line is played from
 * sound/radio in the user's install and the message is shown on screen. Number keys select; G closes it.
 */
public final class CS16RadioMenu {
    private enum Page { MAIN, STANDARD, GROUP, REPORT }

    private record Cmd(String text, String... keys) {}

    private static final Cmd[] STANDARD = {
            new Cmd("Cover me!", "coverme", "ct_coverme", "cover"),
            new Cmd("You take the point.", "takepoint", "ct_point", "point"),
            new Cmd("Hold this position.", "position", "ct_position", "holdpos"),
            new Cmd("Regroup Team.", "regroup", "ct_regroup"),
            new Cmd("Follow me.", "followme", "ct_followme", "follow"),
            new Cmd("Taking fire... need assistance!", "fireassis", "ct_fireassist", "fireass"),
    };
    private static final Cmd[] GROUP = {
            new Cmd("Go go go!", "com_go", "ct_go", "go"),
            new Cmd("Team, fall back!", "fallback", "ct_fallback"),
            new Cmd("Stick together, team.", "sticktog", "ct_stickt", "stick"),
            new Cmd("Get in position and wait for my go.", "getinpos", "ct_getinpos"),
            new Cmd("Storm the front!", "stormfront", "ct_stormfront", "storm"),
            new Cmd("Report in, team.", "com_reportin", "ct_reportin", "reportin"),
    };
    private static final Cmd[] REPORT = {
            new Cmd("Roger that.", "roger", "ct_affirm", "affirm"),
            new Cmd("Enemy spotted.", "enemyspot", "ct_enemys", "enemys"),
            new Cmd("Need backup.", "needbackup", "ct_backup", "backup"),
            new Cmd("Sector clear.", "clear", "sectorclear", "ct_clear"),
            new Cmd("I'm in position.", "inposition", "ct_inpos", "inpos"),
            new Cmd("Reporting in.", "reportingin", "ct_reportingin"),
            new Cmd("Get out of there, it's gonna blow!", "getout", "ct_getout"),
            new Cmd("Negative.", "negative", "ct_negative"),
            new Cmd("Enemy down.", "enemydown", "ct_enemydown"),
    };

    private static boolean open;
    private static Page page = Page.MAIN;
    private static double closeAt;
    private static String message = "";
    private static double messageUntil;

    private CS16RadioMenu() {}

    public static boolean isOpen() { return open; }

    private static double now() { return System.nanoTime() / 1.0e9; }

    public static void toggle() {
        open = !open;
        page = Page.MAIN;
        closeAt = now() + 10.0;
    }

    /** Number key 1-9 pressed while the menu is open. */
    public static void choose(int n, Minecraft mc) {
        closeAt = now() + 10.0;
        if (page == Page.MAIN) {
            if (n == 1) page = Page.STANDARD;
            else if (n == 2) page = Page.GROUP;
            else if (n == 3) page = Page.REPORT;
            return;
        }
        Cmd[] list = page == Page.STANDARD ? STANDARD : page == Page.GROUP ? GROUP : REPORT;
        if (n < 1 || n > list.length) return;
        Cmd c = list[n - 1];
        String who = mc.player != null ? mc.player.getName().getString() : "Player";
        message = who + " (RADIO): " + c.text();
        messageUntil = now() + 4.0;
        playRadio(c.keys());
        open = false;
    }

    private static void playRadio(String[] keys) {
        CS16SoundManager sm = CS16SoundManager.INSTANCE;
        double v = CS16Config.get().soundVolume;
        sm.playFirst(v * 0.5, "radio/blip1.wav", "radio/blip2.wav");
        String[] names = new String[keys.length];
        for (int i = 0; i < keys.length; i++) names[i] = "radio/" + keys[i] + ".wav";
        if (sm.playAnyOf(v, names)) return;
        for (String k : keys) if (sm.playRandomGroup(v, "sound/radio/" + k)) return; // discover by prefix if names differ
        CS16Client.ASSETS.errors().add("radio line not found for: " + keys[0]);
    }

    public static void draw(GuiGraphicsExtractor g, Minecraft mc, int sw, int sh) {
        double t = now();
        if (open && t > closeAt) open = false;
        int orange = 0xFFFFA000, white = 0xFFFFE0A0;

        if (t < messageUntil) {
            g.text(mc.font, message, 10, (int) (sh * 0.62), orange);
        }
        if (!open) return;

        String title;
        String[] lines;
        switch (page) {
            case STANDARD -> { title = "Standard Radio"; lines = texts(STANDARD); }
            case GROUP -> { title = "Group Radio"; lines = texts(GROUP); }
            case REPORT -> { title = "Radio Report"; lines = texts(REPORT); }
            default -> { title = "Radio Message"; lines = new String[]{"Standard Radio", "Group Radio", "Report"}; }
        }
        int x = 10, y = (int) (sh * 0.34);
        g.fill(x - 6, y - 6, x + 230, y + 14 + (lines.length + 2) * 11, 0x80000000);
        g.text(mc.font, title, x, y, orange);
        y += 14;
        for (int i = 0; i < lines.length; i++) {
            g.text(mc.font, (i + 1) + ". " + lines[i], x, y, white);
            y += 11;
        }
        y += 11;
        g.text(mc.font, "G. Exit", x, y, white);
    }

    private static String[] texts(Cmd[] list) {
        String[] out = new String[list.length];
        for (int i = 0; i < list.length; i++) out[i] = list[i].text();
        return out;
    }
}
