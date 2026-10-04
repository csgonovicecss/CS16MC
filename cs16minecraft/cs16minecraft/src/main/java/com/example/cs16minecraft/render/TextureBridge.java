package com.example.cs16minecraft.render;

import com.example.cs16minecraft.CS16Minecraft;
import com.example.cs16minecraft.client.CS16Client;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The ONLY place that turns converted CS images into GPU textures and draws them. Render thread only.
 * If a Minecraft 26.2 API name differs (NativeImage / DynamicTexture / blit), it is fixed here.
 */
public final class TextureBridge {
    public record Tex(Identifier id, int w, int h) {}

    private static final Map<String, Tex> CACHE = new HashMap<>();

    private TextureBridge() {}

    /** Returns the uploaded HUD sprite, or null while assets are still converting / sprite is absent. */
    public static Tex hud(String name) {
        Tex t = CACHE.get(name);
        if (t != null) return t;
        BufferedImage img = CS16Client.ASSETS.hudSprite(name);
        if (img == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            NativeImage ni = NativeImage.read(new ByteArrayInputStream(out.toByteArray()));
            Identifier id = Identifier.fromNamespaceAndPath(CS16Minecraft.MOD_ID,
                    "hud/" + name.toLowerCase().replaceAll("[^a-z0-9._-]", "_"));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, ni));
            t = new Tex(id, img.getWidth(), img.getHeight());
            CACHE.put(name, t);
            return t;
        } catch (Exception e) {
            CS16Client.ASSETS.errors().add("texture upload " + name + ": " + e);
            return null;
        }
    }

    /** Draws the whole texture scaled to w x h, tinted by ARGB colour. */
    public static void draw(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int argb) {
        g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0f, 0f, w, h, t.w(), t.h(), t.w(), t.h(), argb);
    }

    /** Draws a source rectangle (srcY..srcY+srcH, full width) of a texture into x,y,w,h. */
    public static void drawPart(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int srcY, int srcH, int argb) {
        g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0f, (float) srcY, w, h, t.w(), srcH, t.w(), t.h(), argb);
    }

    /** Draws a region of any registered texture, stretched to w x h. */
    public static void drawRegion(GuiGraphicsExtractor g, Identifier id, int x, int y, int w, int h,
                                  int u, int v, int regionW, int regionH, int texW, int texH, int argb) {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, (float) u, (float) v, w, h, regionW, regionH, texW, texH, argb);
    }
}
