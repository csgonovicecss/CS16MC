package com.example.cs16minecraft.assets;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the user's Half-Life / Counter-Strike 1.6 install without a hardcoded path.
 * Order: configured path -> Steam from the Windows registry / env / default locations / other drives ->
 * every Steam library listed in libraryfolders.vdf -> app manifests (Half-Life = 70, Counter-Strike = 10)
 * -> any folder under steamapps/common that contains a "cstrike" directory.
 * Read-only: it only looks, never writes.
 */
public final class SteamLocator {
    private SteamLocator() {}

    public record Result(Path path, List<String> tried) {}

    private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");
    private static final Pattern VDF_OLD = Pattern.compile("\"\\d+\"\\s+\"([^\"]+)\"");
    private static final Pattern INSTALLDIR = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"");
    private static final String[] APP_MANIFESTS = {"appmanifest_70.acf", "appmanifest_10.acf"};

    /** @param configured user-configured path (may be blank or stale); @param extraSteamRoots extra places to treat as Steam installs. */
    public static Result locate(String configured, List<Path> extraSteamRoots) {
        List<String> tried = new ArrayList<>();

        if (configured != null && !configured.isBlank()) {
            Path p = safe(configured);
            if (p != null) {
                tried.add(p + " (configured)");
                Path hit = valid(p);
                if (hit != null) return new Result(hit, tried);
            }
        }

        Set<Path> steamRoots = new LinkedHashSet<>();
        if (extraSteamRoots != null) steamRoots.addAll(extraSteamRoots);
        steamRoots.addAll(registrySteamRoots());
        steamRoots.addAll(defaultSteamRoots());
        steamRoots.addAll(driveSteamRoots());

        Set<Path> libraries = new LinkedHashSet<>();
        for (Path root : steamRoots) {
            if (!Files.isDirectory(root)) continue;
            libraries.add(root);
            libraries.addAll(libraryFolders(root));
        }

        for (Path lib : libraries) {
            Path steamapps = lib.resolve("steamapps");
            Path common = steamapps.resolve("common");
            for (String manifest : APP_MANIFESTS) {
                String dir = installDir(steamapps.resolve(manifest));
                if (dir != null) {
                    Path p = common.resolve(dir);
                    tried.add(p + " (from " + manifest + ")");
                    Path hit = valid(p);
                    if (hit != null) return new Result(hit, tried);
                }
            }
            Path std = common.resolve("Half-Life");
            tried.add(std.toString());
            Path hit = valid(std);
            if (hit != null) return new Result(hit, tried);
            // any game folder in this library that has a cstrike directory (renamed installs, CS 1.6 standalone)
            if (Files.isDirectory(common)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(common)) {
                    for (Path game : ds) {
                        Path found = valid(game);
                        if (found != null) { tried.add(game + " (cstrike folder found)"); return new Result(found, tried); }
                    }
                } catch (IOException ignored) { }
            }
        }
        if (libraries.isEmpty()) tried.add("(no Steam installation found)");
        return new Result(null, tried);
    }

    // ------------------------------------------------------------------ pieces

    private static Path valid(Path p) {
        try {
            if (p != null && Files.isDirectory(p.resolve("cstrike"))) return p;
        } catch (Exception ignored) { }
        return null;
    }

    private static Path safe(String s) {
        try { return Paths.get(s.trim().replace("\"", "")); } catch (Exception e) { return null; }
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    /** Steam location from the Windows registry via the built-in reg.exe (no native code needed). */
    private static List<Path> registrySteamRoots() {
        List<Path> out = new ArrayList<>();
        if (!isWindows()) return out;
        String[][] queries = {
                {"HKCU\\Software\\Valve\\Steam", "SteamPath"},
                {"HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam", "InstallPath"},
                {"HKLM\\SOFTWARE\\Valve\\Steam", "InstallPath"}
        };
        for (String[] q : queries) {
            try {
                Process proc = new ProcessBuilder("reg", "query", q[0], "/v", q[1]).redirectErrorStream(true).start();
                String text = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                proc.waitFor(3, TimeUnit.SECONDS);
                for (String line : text.split("\\R")) {
                    int i = line.indexOf("REG_SZ");
                    if (i >= 0) {
                        Path p = safe(line.substring(i + 6).trim());
                        if (p != null) out.add(p);
                    }
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static List<Path> defaultSteamRoots() {
        List<Path> out = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        String[] env = {"ProgramFiles(x86)", "ProgramFiles", "ProgramW6432"};
        for (String e : env) {
            String v = System.getenv(e);
            if (v != null && !v.isEmpty()) out.add(Paths.get(v, "Steam"));
        }
        if (!home.isEmpty()) {
            out.add(Paths.get(home, ".steam", "steam"));
            out.add(Paths.get(home, ".steam", "root"));
            out.add(Paths.get(home, ".local", "share", "Steam"));
            out.add(Paths.get(home, ".var", "app", "com.valvesoftware.Steam", ".local", "share", "Steam"));
            out.add(Paths.get(home, "Library", "Application Support", "Steam"));
        }
        return out;
    }

    /** Steam / library folders in the usual spots on every drive (D:\SteamLibrary, E:\Games\Steam, ...). */
    private static List<Path> driveSteamRoots() {
        List<Path> out = new ArrayList<>();
        if (!isWindows()) return out;
        String[] rel = {"Steam", "SteamLibrary", "Steam Library", "Program Files (x86)\\Steam", "Program Files\\Steam",
                "Games\\Steam", "Games\\SteamLibrary", "Games\\Steam Library"};
        for (File drive : File.listRoots()) {
            Path root = drive.toPath();
            for (String r : rel) out.add(root.resolve(r));
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) { // any top-level folder with "steam" in its name
                for (Path d : ds) {
                    String n = d.getFileName() == null ? "" : d.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.contains("steam") && Files.isDirectory(d)) out.add(d);
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Library paths listed in steamapps/libraryfolders.vdf (current and legacy formats). */
    static List<Path> libraryFolders(Path steamRoot) {
        List<Path> out = new ArrayList<>();
        for (Path vdf : new Path[]{steamRoot.resolve("steamapps").resolve("libraryfolders.vdf"),
                steamRoot.resolve("config").resolve("libraryfolders.vdf")}) {
            if (!Files.isRegularFile(vdf)) continue;
            try {
                String text = Files.readString(vdf, StandardCharsets.UTF_8);
                for (Pattern pat : new Pattern[]{VDF_PATH, VDF_OLD}) {
                    Matcher m = pat.matcher(text);
                    while (m.find()) {
                        Path p = safe(m.group(1).replace("\\\\", "\\"));
                        if (p != null) out.add(p);
                    }
                }
            } catch (IOException ignored) { }
        }
        return out;
    }

    static String installDir(Path acf) {
        try {
            if (!Files.isRegularFile(acf)) return null;
            Matcher m = INSTALLDIR.matcher(Files.readString(acf, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
