package com.example.cs16minecraft.config;

import com.example.cs16minecraft.CS16Minecraft;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/cs16minecraft.json */
public final class CS16Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile CS16Config instance = new CS16Config();

    /** Leave empty to auto-detect Steam and its libraries. Set it only to force a specific Half-Life folder (the one containing "cstrike"). */
    public String cs16AssetsPath = "";
    public boolean infiniteAmmo = true;
    public int fov = 90;
    /** Vanilla slider value, 0.0 - 1.0. */
    public double mouseSensitivity = 0.5;
    public double hudScale = 1.0;
    public boolean debug = false;
    /** Unbinds vanilla E / Q / Ctrl / Shift / Tab on first launch so CS keys do not conflict. */
    public boolean unbindVanillaKeys = true;
    public boolean movementEnabled = true;
    /** Seconds on the death screen before auto-respawn. */
    public double respawnDelay = 3.0;
    public double soundVolume = 0.8;
    /** CS damage is out of 100 hp; Minecraft players/mobs have ~20. 0.2 maps 100 -> 20. */
    public double damageScale = 0.2;
    /** Bullets and knife damage blocks. A block's hp = its hardness x this (stone 1.5 -> ~225, so ~7 AK-47 hits). */
    public boolean blockDamage = true;
    public double blockHpPerHardness = 150.0;
    /** HE grenades carve a crater. */
    public boolean grenadeCrater = true;
    /** Gunshots attract hostile mobs and scare villagers/cats; radius depends on the weapon (silenced = much quieter). */
    public boolean mobAlerts = true;
    public double craterRadius = 3.2;
    /** Idle HUD brightness (GoldSrc MIN_ALPHA = 100). */
    public int hudMinAlpha = 100;

    public Movement movement = new Movement();
    public Crosshair crosshair = new Crosshair();
    public Viewmodel viewmodel = new Viewmodel();
    public Cache cache = new Cache();

    /** GoldSrc / CS 1.6 defaults. Speeds are in GoldSrc units per second. */
    public static final class Movement {
        public double maxSpeed = 250.0;
        public double walkFactor = 0.52;
        public double duckFactor = 0.333;
        public double accelerate = 5.0;
        public double airAccelerate = 10.0;
        public double airSpeedCap = 30.0;
        public double friction = 4.0;
        public double stopSpeed = 75.0;
        public double edgeFriction = 2.0;
        public double gravity = 800.0;
        public double jumpSpeed = 268.3281572999747;
        public double maxVelocity = 2000.0;
        /** Scales ground acceleration (sv_accelerate x this). 1.0 = sluggish, 4.0 = near instant. ~1.6 reaches top speed in ~0.15s. */
        public double groundAccelMultiplier = 1.6;
        public boolean autoBhop = false;
        public boolean bhopCap = true;
        public double bhopCapFactor = 1.2;
        /** 72 GoldSrc units = 1.8 blocks. */
        public double unitsPerBlock = 40.0;
        public double tickSeconds = 0.05;
        /** Physics steps per Minecraft tick. 5 = 100 Hz, like GoldSrc's usual frame rate. */
        public int substeps = 5;
    }

    public static final class Crosshair {
        public int size = 5;
        public int thickness = 1;
        public int gap = 3;
        public int red = 0, green = 255, blue = 0;
        public int alpha = 200;
        public boolean dynamic = true;
        public boolean outline = true;
    }

    /** Offsets applied to the viewmodel (used by the Stage 2 weapon renderer). */
    public static final class Viewmodel {
        public double offsetX = 0, offsetY = 0, offsetZ = 0;
        public double bob = 1.0;
        /** Viewmodel render resolution as a fraction of the window (1.0 = native). Lower it if FPS drops. */
        public double renderScale = 0.75;
        public boolean enabled = true;
        /** Toggled with H: weapon drawn on the left side of the screen. */
        public boolean mirrored = false;
    }

    public static final class Cache {
        public boolean enabled = true;
        /** Empty = <gameDir>/cs16cache. Must not be inside the Half-Life folder. */
        public String directory = "";
        public boolean rebuildOnStart = false;
    }

    public static CS16Config get() { return instance; }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("cs16minecraft.json");
    }

    public static void load() {
        Path f = file();
        try {
            if (Files.exists(f)) {
                CS16Config c = GSON.fromJson(Files.readString(f), CS16Config.class);
                if (c != null) instance = c;
            }
            save(); // writes defaults and fills in any newly added fields
        } catch (Exception e) {
            CS16Minecraft.LOGGER.error("Could not read {}, using defaults", f, e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(instance));
        } catch (IOException e) {
            CS16Minecraft.LOGGER.error("Could not write config", e);
        }
    }
}
