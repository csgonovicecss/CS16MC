package com.example.cs16minecraft.assets;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads WAD3 texture archives (mip-textures) and converts the base mip level to ARGB images. */
public final class GoldSrcTextureLoader {
    private GoldSrcTextureLoader() {}

    public static Map<String, BufferedImage> loadWad(Path file) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        int magic = b.getInt();
        if (magic != 0x33444157) throw new IOException("not a WAD3 file: " + file.getFileName()); // "WAD3"
        int lumps = b.getInt();
        int tableOffset = b.getInt();
        Map<String, BufferedImage> out = new LinkedHashMap<>();
        for (int i = 0; i < lumps; i++) {
            int e = tableOffset + i * 32;
            int filePos = b.getInt(e);
            int type = b.get(e + 12) & 0xFF;
            if (type != 0x43) continue; // miptex only
            String name = cstr(b, e + 16, 16);
            out.put(name.toLowerCase(), readMipTex(b, filePos, name.startsWith("{")));
        }
        return out;
    }

    private static BufferedImage readMipTex(ByteBuffer b, int pos, boolean masked) {
        int w = b.getInt(pos + 16);
        int h = b.getInt(pos + 20);
        int off0 = b.getInt(pos + 24);
        int palPos = pos + off0 + w * h * 85 / 64 + 2; // all four mips, then a 2-byte colour count
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < w * h; i++) {
            int idx = b.get(pos + off0 + i) & 0xFF;
            int r = b.get(palPos + idx * 3) & 0xFF, g = b.get(palPos + idx * 3 + 1) & 0xFF, bl = b.get(palPos + idx * 3 + 2) & 0xFF;
            int a = (masked && idx == 255) ? 0 : 255;
            img.setRGB(i % w, i / w, (a << 24) | (r << 16) | (g << 8) | bl);
        }
        return img;
    }

    static String cstr(ByteBuffer b, int off, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            byte c = b.get(off + i);
            if (c == 0) break;
            sb.append((char) (c & 0xFF));
        }
        return sb.toString();
    }
}
