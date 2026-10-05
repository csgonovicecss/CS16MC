package com.example.cs16minecraft.input;

import com.example.cs16minecraft.CS16Minecraft;
import com.example.cs16minecraft.config.CS16Config;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.resources.Identifier;

/**
 * CS-style key bindings. W/A/S/D, Space, mouse buttons keep vanilla's mappings (which already match CS).
 * New mappings cover crouch, walk, reload, quick switch, use, scoreboard. The conflicting vanilla bindings
 * (E inventory, Q drop, Ctrl sprint, Shift sneak, Tab list) are unbound once on startup.
 */
public final class CS16Input {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(CS16Minecraft.MOD_ID, "main"));

    public static final KeyMapping CROUCH = key("crouch", InputConstants.KEY_LCONTROL);
    public static final KeyMapping WALK = key("walk", InputConstants.KEY_LSHIFT);
    public static final KeyMapping RELOAD = key("reload", InputConstants.KEY_R);
    public static final KeyMapping QUICK_SWITCH = key("quickswitch", InputConstants.KEY_Q);
    public static final KeyMapping USE = key("use", InputConstants.KEY_E);
    public static final KeyMapping SCOREBOARD = key("scoreboard", InputConstants.KEY_TAB);
    public static final KeyMapping MIRROR = key("mirror", InputConstants.KEY_H);
    public static final KeyMapping DEBUG = key("debug", InputConstants.KEY_F8);

    private CS16Input() {}

    private static KeyMapping key(String name, int code) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + CS16Minecraft.MOD_ID + "." + name, InputConstants.Type.KEYSYM, code, CATEGORY));
    }

    public static void register() { /* static initialiser does the work; call forces class load */ }

    public static void tick() {
        while (DEBUG.consumeClick()) {
            CS16Config.get().debug = !CS16Config.get().debug;
            CS16Config.save();
        }
    }

    public static void unbindVanilla(Options o) {
        o.keyInventory.setKey(InputConstants.UNKNOWN);
        o.keyDrop.setKey(InputConstants.UNKNOWN);
        o.keySprint.setKey(InputConstants.UNKNOWN);
        o.keyShift.setKey(InputConstants.UNKNOWN);
        o.keyPlayerList.setKey(InputConstants.UNKNOWN);
        KeyMapping.resetMapping();
        o.save();
    }
}
