package com.example.cs16minecraft.render;

import com.example.cs16minecraft.CS16Minecraft;
import com.example.cs16minecraft.assets.GoldSrcModelLoader.ModelData;
import com.example.cs16minecraft.assets.GoldSrcModelLoader.Sequence;
import com.example.cs16minecraft.assets.GoldSrcModelLoader.SubModel;
import com.example.cs16minecraft.client.CS16Client;
import com.example.cs16minecraft.config.CS16Config;
import com.example.cs16minecraft.weapon.CS16GrenadeEntity;
import com.example.cs16minecraft.weapon.CS16WeaponManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * First-person weapon + thrown grenades from the real v_*.mdl / w_*.mdl models: posed from their animation data,
 * software-rasterised (parallel, bilinear) at near-native resolution, lit by the world light, uploaded and drawn.
 *
 * Speed: the GPU texture only covers the area the weapon actually occupies (it grows with a margin when needed),
 * and pixels are copied with bulk memory writes instead of one setPixel call each.
 */
public final class CS16WeaponRenderer {
    private static final int MAX_W = 2560, MAX_H = 1440, MARGIN = 48;

    private final CS16ModelRenderer raster = new CS16ModelRenderer(640, 360);
    private DynamicTexture texture;
    private Identifier texId;
    private int regX, regY, regW, regH;       // screen-render-space rectangle the texture covers
    private int frameW, frameH, texCounter;
    private boolean failed;
    private double bobTime;
    private double light = 1.0;
    private int[] lastRect;
    private int[] rowTmp = new int[0];
    private final Map<String, int[]> spritePixels = new HashMap<>();
    private final Map<String, int[]> spriteDims = new HashMap<>();

    private void dropTexture() {
        if (texId != null) {
            Minecraft.getInstance().getTextureManager().release(texId);
        }
        texture = null;
        texId = null;
        lastRect = null;
    }

    /** Makes sure the texture covers the rectangle, growing (with margin) only when it does not. */
    private boolean ensureRegion(int[] need) {
        if (failed) return false;
        if (texture != null && need[0] >= regX && need[1] >= regY && need[2] < regX + regW && need[3] < regY + regH) return true;
        try {
            int x0 = need[0], y0 = need[1], x1 = need[2], y1 = need[3];
            if (texture != null) { x0 = Math.min(x0, regX); y0 = Math.min(y0, regY); x1 = Math.max(x1, regX + regW - 1); y1 = Math.max(y1, regY + regH - 1); }
            x0 = Math.max(0, x0 - MARGIN); y0 = Math.max(0, y0 - MARGIN);
            x1 = Math.min(frameW - 1, x1 + MARGIN); y1 = Math.min(frameH - 1, y1 + MARGIN);
            dropTexture();
            regX = x0; regY = y0; regW = x1 - x0 + 1; regH = y1 - y0 + 1;
            NativeImage ni = new NativeImage(NativeImage.Format.RGBA, regW, regH, true);
            texId = Identifier.fromNamespaceAndPath(CS16Minecraft.MOD_ID, "viewmodel_" + (texCounter++));
            texture = new DynamicTexture(texId::toString, ni);
            Minecraft.getInstance().getTextureManager().register(texId, texture);
            if (rowTmp.length < regW) rowTmp = new int[regW];
            return true;
        } catch (Exception e) {
            failed = true;
            CS16Client.ASSETS.errors().add("viewmodel texture: " + e);
            return false;
        }
    }

    public void render(GuiGraphicsExtractor g, int sw, int sh, double dt, LocalPlayer p, float partial, double guiScale) {
        CS16Config cfg = CS16Config.get();
        CS16WeaponManager wm = CS16WeaponManager.INSTANCE;
        if (!cfg.viewmodel.enabled || failed) return;

        double scale = Math.max(0.25, Math.min(1.5, cfg.viewmodel.renderScale));
        int W = (int) Math.max(160, Math.min(MAX_W, Math.round(sw * guiScale * scale)));
        int H = (int) Math.max(90, Math.min(MAX_H, Math.round(W * (double) sh / sw)));
        if (W != frameW || H != frameH) { frameW = W; frameH = H; dropTexture(); }

        // world light at the player's eyes: torches, lava, glowstone, daylight and night all feed this
        Level level = p.level();
        int raw = level.getMaxLocalRawBrightness(BlockPos.containing(p.getEyePosition(partial)));
        double target = 0.14 + 0.86 * Math.pow(raw / 15.0, 0.9);
        light += (target - light) * Math.min(1.0, dt * 6.0);
        float lt = (float) light;

        double vfov = Math.toRadians(Minecraft.getInstance().options.fov().get());
        raster.begin(W, H, vfov);

        drawGrenades(p, partial, lt, level, wm);

        ModelData m = wm.currentModel();
        Sequence seq = wm.currentSequence();
        boolean mirrored = cfg.viewmodel.mirrored;
        boolean vm = wm.viewmodelVisible() && m != null && seq != null && !m.bones.isEmpty() && m.sequences.contains(seq);
        float[] flashPos = null;
        if (vm) {
            raster.setMirror(mirrored);
            flashPos = drawViewmodel(m, seq, wm, cfg, dt, lt);
            raster.setMirror(false);
        }
        raster.flush();
        if (vm && wm.muzzleActive() && flashPos != null) drawMuzzleFlash(wm, flashPos, mirrored);

        int[] rect = raster.dirty();
        if (rect == null && lastRect == null) return;
        if (rect != null && !ensureRegion(rect)) return;
        if (texture == null) return;
        int[] u = union(rect, lastRect);
        if (u == null) return;
        NativeImage px = texture.getPixels();
        if (px == null) return;

        // bulk copy: ARGB int[] rows -> RGBA native memory (ABGR ints on little-endian)
        long ptr = px.getPointer();
        int[] color = raster.color();
        int stride = raster.stride();
        int ux0 = Math.max(u[0], regX), ux1 = Math.min(u[2], regX + regW - 1);
        int uy0 = Math.max(u[1], regY), uy1 = Math.min(u[3], regY + regH - 1);
        int len = ux1 - ux0 + 1;
        if (len > 0) {
            for (int y = uy0; y <= uy1; y++) {
                int src = y * stride + ux0;
                for (int i = 0; i < len; i++) {
                    int c = color[src + i];
                    rowTmp[i] = (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF);
                }
                IntBuffer dst = MemoryUtil.memIntBuffer(ptr + ((long) (y - regY) * regW + (ux0 - regX)) * 4L, len);
                dst.put(rowTmp, 0, len);
            }
            texture.upload();
        }
        lastRect = rect;

        if (rect != null) {
            double sx = sw / (double) W, sy = sh / (double) H;
            int dx = (int) Math.floor(regX * sx), dy = (int) Math.floor(regY * sy);
            int dw = (int) Math.ceil((regX + regW) * sx) - dx, dh = (int) Math.ceil((regY + regH) * sy) - dy;
            TextureBridge.drawRegion(g, texId, dx, dy, dw, dh, 0, 0, regW, regH, regW, regH, 0xFFFFFFFF);
        }
    }

    private static int[] union(int[] a, int[] b) {
        if (a == null) return b;
        if (b == null) return a;
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    /** @return view-space muzzle position {x,y,z} (already mirrored-agnostic) or null */
    private float[] drawViewmodel(ModelData m, Sequence seq, CS16WeaponManager wm, CS16Config cfg, double dt, float lt) {
        double frame = wm.currentFrame();
        int f0 = (int) Math.floor(frame);
        double frac = frame - f0;
        int n = Math.max(1, seq.numFrames);
        boolean loop = wm.sequenceLoops();
        int fa = loop ? Math.floorMod(f0, n) : Math.min(f0, n - 1);
        int fb = loop ? Math.floorMod(f0 + 1, n) : Math.min(f0 + 1, n - 1);
        float[] bones = m.poseBones(seq, fa);
        if (fa != fb && frac > 0.001) {
            float[] b2 = m.poseBones(seq, fb);
            float t = (float) frac;
            float[] mix = new float[bones.length];
            for (int i = 0; i < mix.length; i++) mix[i] = bones[i] + (b2[i] - bones[i]) * t;
            bones = mix;
        }

        var ctrl = CS16Client.MOVEMENT.controller;
        double speed = ctrl.horizontalSpeed();
        bobTime += dt * (0.5 + speed / 250.0 * 1.4);
        double amp = Math.min(speed * 0.01, 4.0) * cfg.viewmodel.bob * (ctrl.grounded ? 1.0 : 0.15);
        float ox = (float) cfg.viewmodel.offsetX - (float) (wm.kick() * 1.1);
        float oy = (float) cfg.viewmodel.offsetY + (float) (Math.sin(bobTime * 6.0) * amp * 0.35);
        float oz = (float) cfg.viewmodel.offsetZ - (float) (Math.abs(Math.cos(bobTime * 6.0)) * amp * 0.4) + (float) (wm.kick() * 0.4);

        double swing = wm.swing();
        if (swing > 0) { ox += (float) (4 * swing); oy -= (float) (9 * swing); oz -= (float) (3 * swing); }

        List<List<SubModel>> parts = m.bodyParts;
        if (parts.isEmpty()) {
            if (!m.subModels.isEmpty()) raster.drawSubModel(m, m.subModels.get(0), bones, ox, oy, oz, lt);
        } else {
            for (List<SubModel> part : parts) {
                if (part.isEmpty()) continue;
                raster.drawSubModel(m, part.get(Math.min(wm.bodyChoice(part), part.size() - 1)), bones, ox, oy, oz, lt);
            }
        }
        float[] ap = m.attachmentPoint(0, bones);
        return ap == null ? null : new float[]{ap[0] + ox, ap[1] + oy, ap[2] + oz};
    }

    /** Thrown grenades as their real w_*.mdl models, tumbling, hidden when a block is between them and the camera. */
    private void drawGrenades(LocalPlayer p, float partial, float lt, Level level, CS16WeaponManager wm) {
        List<CS16GrenadeEntity> list = wm.grenades.grenades;
        if (list.isEmpty()) return;
        Vec3 eye = p.getEyePosition(partial);
        Vec3 look = p.getViewVector(partial);
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 up = right.cross(look).normalize();
        for (CS16GrenadeEntity gr : list) {
            Vec3 rel = gr.pos.subtract(eye);
            double fwd = rel.dot(look);
            if (fwd < 0.2) continue;
            BlockHitResult r = level.clip(new ClipContext(eye, gr.pos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (r.getType() != HitResult.Type.MISS && r.getLocation().distanceTo(gr.pos) > 0.25) continue; // behind a wall

            String name = switch (gr.type) {
                case HE -> "models/w_hegrenade.mdl";
                case FLASH -> "models/w_flashbang.mdl";
                default -> "models/w_smokegrenade.mdl";
            };
            ModelData m = CS16Client.ASSETS.model(name);
            if (m == null || m.bones.isEmpty() || m.sequences.isEmpty()) continue;
            float[] bones = m.poseBones(m.sequences.get(0), 0);

            double a = gr.age * 13.0, b = gr.age * 7.0 + 0.5;
            double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b);
            float[][] rot = {
                    {(float) (ca * cb), (float) -sa, (float) (ca * sb)},
                    {(float) (sa * cb), (float) ca, (float) (sa * sb)},
                    {(float) -sb, 0f, (float) cb}};
            float[] t = {(float) (fwd * 40.0), (float) (-rel.dot(right) * 40.0), (float) (rel.dot(up) * 40.0)};
            float[] mats = transform(bones, rot, t);
            if (m.bodyParts.isEmpty()) {
                if (!m.subModels.isEmpty()) raster.drawSubModel(m, m.subModels.get(0), mats, 0, 0, 0, lt);
            } else {
                for (List<SubModel> part : m.bodyParts) if (!part.isEmpty()) raster.drawSubModel(m, part.get(0), mats, 0, 0, 0, lt);
            }
        }
    }

    private static float[] transform(float[] b, float[][] r, float[] t) {
        float[] out = new float[b.length];
        for (int i = 0; i + 11 < b.length; i += 12) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    out[i + row * 4 + col] = r[row][0] * b[i + col] + r[row][1] * b[i + 4 + col] + r[row][2] * b[i + 8 + col];
                }
                out[i + row * 4 + 3] = r[row][0] * b[i + 3] + r[row][1] * b[i + 7] + r[row][2] * b[i + 11] + t[row];
            }
        }
        return out;
    }

    private void drawMuzzleFlash(CS16WeaponManager wm, float[] pos, boolean mirrored) {
        String rel = "sprites/" + wm.current.flash + ".spr";
        int[] pix = spritePixels.get(rel);
        if (pix == null) {
            BufferedImage img = CS16Client.ASSETS.rawSprite(rel);
            if (img == null) return;
            pix = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
            spritePixels.put(rel, pix);
            spriteDims.put(rel, new int[]{img.getWidth(), img.getHeight()});
        }
        int[] d = spriteDims.get(rel);
        float size = 9f + ThreadLocalRandom.current().nextFloat() * 4f;
        raster.drawSprite(pix, d[0], d[1], pos[0], mirrored ? -pos[1] : pos[1], pos[2], size, 1.0f);
    }
}
