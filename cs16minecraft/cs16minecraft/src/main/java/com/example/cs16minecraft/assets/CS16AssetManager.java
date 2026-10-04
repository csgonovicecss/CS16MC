package com.example.cs16minecraft.assets;

import com.example.cs16minecraft.CS16Minecraft;
import com.example.cs16minecraft.config.CS16Config;
import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Locates the user's installed Half-Life / CS 1.6 files, indexes them, and converts the ones the mod needs
 * into runtime representations. Everything runs on a background thread; the render thread only reads
 * finished results. Originals are never modified; converted data is cached outside the Half-Life folder.
 */
public final class CS16AssetManager {
    public enum State { IDLE, SCANNING, READY, MISSING_INSTALL, ERROR }

    private volatile State state = State.IDLE;
    private volatile String status = "not started";
    private volatile Path root;
    private volatile Path cacheDir;

    private final Map<String, Path> files = new ConcurrentHashMap<>();            // "models/v_ak47.mdl" -> path
    private final Map<String, BufferedImage> hudSprites = new ConcurrentHashMap<>();
    private final Map<String, GoldSrcModelLoader.ModelData> models = new ConcurrentHashMap<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final AtomicInteger modelFiles = new AtomicInteger(), spriteFiles = new AtomicInteger(), soundFiles = new AtomicInteger();

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CS16-Assets");
        t.setDaemon(true);
        return t;
    });

    public void startAsync() {
        state = State.SCANNING;
        exec.submit(() -> {
            try {
                run();
            } catch (Throwable t) {
                state = State.ERROR;
                status = "asset loading failed: " + t;
                errors.add(status);
                CS16Minecraft.LOGGER.error("Asset loading failed", t);
            }
        });
    }

    private void run() throws IOException {
        CS16Config cfg = CS16Config.get();
        status = "looking for Half-Life / Counter-Strike 1.6...";
        SteamLocator.Result found = SteamLocator.locate(cfg.cs16AssetsPath, java.util.List.of());
        if (found.path() == null) {
            state = State.MISSING_INSTALL;
            int n = Math.min(6, found.tried().size());
            status = "Half-Life + Counter-Strike 1.6 not found (searched Steam automatically). Install them via Steam"
                    + " or set cs16AssetsPath in config/cs16minecraft.json. Looked in: "
                    + String.join("; ", found.tried().subList(0, n));
            CS16Minecraft.LOGGER.error("{} | all locations: {}", status, found.tried());
            return;
        }
        Path hl = found.path();
        CS16Minecraft.LOGGER.info("CS16: using game files from {}", hl);
        root = hl;

        Path cache = cfg.cache.directory == null || cfg.cache.directory.isBlank()
                ? FabricLoader.getInstance().getGameDir().resolve("cs16cache")
                : Paths.get(cfg.cache.directory);
        if (cache.toAbsolutePath().normalize().startsWith(hl.toAbsolutePath().normalize())) {
            state = State.ERROR;
            status = "cache directory must not be inside the Half-Life folder: " + cache;
            errors.add(status);
            return;
        }
        cacheDir = cache;
        if (cfg.cache.enabled) Files.createDirectories(cache);

        status = "scanning files...";
        for (String mod : new String[]{"cstrike", "valve"}) { // cstrike overrides valve
            Path base = hl.resolve(mod);
            if (!Files.isDirectory(base)) continue;
            for (String sub : new String[]{"models", "sprites", "sound"}) {
                Path dir = base.resolve(sub);
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile).forEach(p -> {
                        String rel = base.relativize(p).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                        if (files.putIfAbsent(rel, p) == null) {
                            if (rel.endsWith(".mdl")) modelFiles.incrementAndGet();
                            else if (rel.endsWith(".spr")) spriteFiles.incrementAndGet();
                            else if (rel.endsWith(".wav")) soundFiles.incrementAndGet();
                        }
                    });
                }
            }
        }

        status = "converting HUD sprites...";
        loadHud();
        status = "parsing viewmodels...";
        for (String rel : files.keySet()) {
            if ((rel.startsWith("models/v_") && rel.endsWith(".mdl"))
                    || rel.equals("models/w_hegrenade.mdl") || rel.equals("models/w_flashbang.mdl") || rel.equals("models/w_smokegrenade.mdl")) loadModel(rel);
        }
        state = State.READY;
        status = "ready";
    }

    private volatile int hudRes = 640;
    private final Map<String, BufferedImage> rawSprites = new ConcurrentHashMap<>();

    private void loadHud() {
        Map<String, BufferedImage> sheets = new ConcurrentHashMap<>();
        Path hudTxt = files.get("sprites/hud.txt");
        if (hudTxt == null) errors.add("sprites/hud.txt not found");
        else loadSpriteList(hudTxt, "", sheets);
        for (Map.Entry<String, Path> e : files.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("sprites/weapon_") && k.endsWith(".txt")) {
                String base = k.substring("sprites/".length(), k.length() - 4); // e.g. weapon_ak47
                loadSpriteList(e.getValue(), base + "/", sheets);
            }
        }
    }

    /** hud.txt and weapon_*.txt share one format; weapon entries are stored as "weapon_ak47/ammo". */
    private void loadSpriteList(Path txt, String keyPrefix, Map<String, BufferedImage> sheets) {
        try {
            for (HudSpriteDefs.Entry e : HudSpriteDefs.parse(txt)) {
                if (e.res() != hudRes) continue;
                BufferedImage sheet = sheets.computeIfAbsent(e.file(), f -> {
                    try { return spriteImage("sprites/" + f + ".spr", true); }
                    catch (IOException ex) { errors.add("sprite " + f + ": " + ex.getMessage()); return null; }
                });
                if (sheet == null) continue;
                int w = Math.min(e.w(), sheet.getWidth() - e.x()), h = Math.min(e.h(), sheet.getHeight() - e.y());
                if (w <= 0 || h <= 0) continue;
                hudSprites.put(keyPrefix + e.name(), sheet.getSubimage(e.x(), e.y(), w, h));
            }
        } catch (IOException ex) {
            errors.add(txt.getFileName() + ": " + ex.getMessage());
        }
    }

    /** Unmodified-colour sprite (muzzle flashes etc.), converted once and cached. Never null once READY. */
    public BufferedImage rawSprite(String rel) {
        BufferedImage c = rawSprites.get(rel);
        if (c != null) return c;
        if (cacheDir == null) return null;
        try {
            c = spriteImage(rel, false);
        } catch (Exception e) {
            errors.add("sprite " + rel + ": " + e.getMessage());
            c = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
        rawSprites.put(rel, c);
        return c;
    }

    /** Converts frame 0 of a sprite, using the PNG cache when it is up to date. */
    private BufferedImage spriteImage(String rel, boolean hudMode) throws IOException {
        Path src = files.get(rel);
        if (src == null) {
            Path direct = root.resolve("cstrike").resolve(rel);
            if (!Files.exists(direct)) throw new IOException("missing " + rel);
            src = direct;
        }
        CS16Config cfg = CS16Config.get();
        Path png = cacheDir.resolve("sprites").resolve(rel.replace('/', '_') + (hudMode ? ".hud.png" : ".png"));
        long srcTime = Files.getLastModifiedTime(src).toMillis();
        if (cfg.cache.enabled && !cfg.cache.rebuildOnStart && Files.exists(png)
                && Files.getLastModifiedTime(png).toMillis() == srcTime) {
            BufferedImage cached = ImageIO.read(png.toFile());
            if (cached != null) return cached;
        }
        BufferedImage img = GoldSrcSpriteLoader.parse(src).toImage(0, hudMode);
        if (cfg.cache.enabled) {
            Files.createDirectories(png.getParent());
            ImageIO.write(img, "png", png.toFile());
            Files.setLastModifiedTime(png, java.nio.file.attribute.FileTime.fromMillis(srcTime));
        }
        return img;
    }

    private void loadModel(String rel) {
        try {
            GoldSrcModelLoader.ModelData m = GoldSrcModelLoader.parse(files.get(rel));
            models.put(rel, m);
            if (CS16Config.get().cache.enabled) { // cache extracted textures as PNG for inspection / later GPU upload
                String base = rel.substring(rel.lastIndexOf('/') + 1, rel.length() - 4);
                Path dir = cacheDir.resolve("models").resolve(base);
                Files.createDirectories(dir);
                for (GoldSrcModelLoader.Texture t : m.textures) {
                    Path out = dir.resolve(t.name.replaceAll("[^A-Za-z0-9._-]", "_") + ".png");
                    if (!Files.exists(out)) ImageIO.write(t.image, "png", out.toFile());
                }
            }
        } catch (Exception e) {
            errors.add(rel + ": " + e.getMessage());
            CS16Minecraft.LOGGER.warn("Model conversion failed for {}", rel, e);
        }
    }

    // ------------------------------------------------------------ accessors (thread-safe)

    public State state() { return state; }
    public String status() { return status; }
    public Path root() { return root; }
    public Path file(String rel) { return files.get(rel.toLowerCase(Locale.ROOT)); }
    /** Indexed relative paths starting with the prefix, sorted. Returns a mutable list. */
    public List<String> listFiles(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new java.util.ArrayList<>();
        for (String k : files.keySet()) if (k.startsWith(p)) out.add(k);
        java.util.Collections.sort(out);
        return out;
    }
    public BufferedImage hudSprite(String name) { return hudSprites.get(name); }
    public GoldSrcModelLoader.ModelData model(String rel) { return models.get(rel.toLowerCase(Locale.ROOT)); }
    public int loadedModels() { return models.size(); }
    public int loadedHudSprites() { return hudSprites.size(); }
    public int modelFileCount() { return modelFiles.get(); }
    public int spriteFileCount() { return spriteFiles.get(); }
    public int soundFileCount() { return soundFiles.get(); }
    public List<String> errors() { return errors; }
}
