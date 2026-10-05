package com.example.cs16minecraft.assets;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parses GoldSrc .spr (IDSP v2) files and converts frames to ARGB images. */
public final class GoldSrcSpriteLoader {
    private GoldSrcSpriteLoader() {}

    public static final int FMT_NORMAL = 0, FMT_ADDITIVE = 1, FMT_INDEXALPHA = 2, FMT_ALPHATEST = 3;

    public static final class Frame {
        public int originX, originY, width, height;
        public byte[] indices;
    }

    public static final class SpriteData {
        public int type, texFormat, width, height;
        public byte[] palette; // RGB triplets
        public final List<Frame> frames = new ArrayList<>();

        /**
         * @param hudMode true = white-tinted image with alpha derived from brightness/index, so the HUD can
         *                colour it like the original engine does (additive, tinted by the HUD colour).
         *                false = colours as stored in the sprite.
         */
        public BufferedImage toImage(int frameIndex, boolean hudMode) {
            Frame f = frames.get(frameIndex);
            BufferedImage img = new BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < f.height; y++) {
                for (int x = 0; x < f.width; x++) {
                    int idx = f.indices[y * f.width + x] & 0xFF;
                    int r = palette[idx * 3] & 0xFF, g = palette[idx * 3 + 1] & 0xFF, b = palette[idx * 3 + 2] & 0xFF;
                    int a = 255;
                    if (hudMode) {
                        if (texFormat == FMT_INDEXALPHA) a = idx;
                        else if (texFormat == FMT_ALPHATEST && idx == 255) a = 0;
                        else a = Math.max(r, Math.max(g, b));
                        r = g = b = 255;
                    } else {
                        switch (texFormat) {
                            case FMT_ADDITIVE -> a = Math.max(r, Math.max(g, b));
                            case FMT_INDEXALPHA -> {
                                a = idx;
                                r = palette[255 * 3] & 0xFF;
                                g = palette[255 * 3 + 1] & 0xFF;
                                b = palette[255 * 3 + 2] & 0xFF;
                            }
                            case FMT_ALPHATEST -> { if (idx == 255) a = 0; }
                            default -> { }
                        }
                    }
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            return img;
        }
    }

    public static SpriteData parse(Path file) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != 0x50534449) throw new IOException("not an IDSP sprite: " + file.getFileName());
        int version = b.getInt();
        if (version != 2) throw new IOException("unsupported sprite version " + version);
        SpriteData s = new SpriteData();
        s.type = b.getInt();
        s.texFormat = b.getInt();
        b.getFloat(); // bounding radius
        s.width = b.getInt();
        s.height = b.getInt();
        int numFrames = b.getInt();
        b.getFloat(); // beam length
        b.getInt();   // sync type
        int colors = b.getShort() & 0xFFFF;
        s.palette = new byte[Math.max(colors, 256) * 3];
        b.get(s.palette, 0, colors * 3);
        for (int i = 0; i < numFrames; i++) {
            int group = b.getInt();
            if (group == 0) {
                s.frames.add(readFrame(b));
            } else {
                int n = b.getInt();
                b.position(b.position() + n * 4); // intervals
                for (int k = 0; k < n; k++) {
                    Frame f = readFrame(b);
                    if (k == 0) s.frames.add(f);
                }
            }
        }
        if (s.frames.isEmpty()) throw new IOException("sprite has no frames");
        return s;
    }

    private static Frame readFrame(ByteBuffer b) {
        Frame f = new Frame();
        f.originX = b.getInt();
        f.originY = b.getInt();
        f.width = b.getInt();
        f.height = b.getInt();
        f.indices = new byte[f.width * f.height];
        b.get(f.indices);
        return f;
    }
}
