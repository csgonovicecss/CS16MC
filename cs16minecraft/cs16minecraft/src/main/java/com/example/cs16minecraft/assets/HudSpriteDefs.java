package com.example.cs16minecraft.assets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parses sprites/hud.txt: "name res file x y w h" records after a leading count. */
public final class HudSpriteDefs {
    private HudSpriteDefs() {}

    public record Entry(String name, int res, String file, int x, int y, int w, int h) {}

    public static List<Entry> parse(Path hudTxt) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(hudTxt, StandardCharsets.ISO_8859_1)) {
            int c = line.indexOf("//");
            sb.append(c >= 0 ? line.substring(0, c) : line).append('\n');
        }
        String[] t = sb.toString().trim().split("\\s+");
        List<Entry> out = new ArrayList<>();
        int i = 1; // t[0] is the entry count
        while (i + 6 < t.length) {
            try {
                out.add(new Entry(t[i].toLowerCase(), Integer.parseInt(t[i + 1]), t[i + 2].toLowerCase(),
                        Integer.parseInt(t[i + 3]), Integer.parseInt(t[i + 4]),
                        Integer.parseInt(t[i + 5]), Integer.parseInt(t[i + 6])));
            } catch (NumberFormatException ignored) {
                // skip malformed record
            }
            i += 7;
        }
        return out;
    }
}
