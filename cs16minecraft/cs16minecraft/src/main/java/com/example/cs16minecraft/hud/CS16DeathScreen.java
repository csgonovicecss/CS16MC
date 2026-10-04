package com.example.cs16minecraft.hud;

import com.example.cs16minecraft.config.CS16Config;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Replaces Minecraft's "You died!" screen with nothing visible, like GoldSrc: no overlay, no text, no buttons.
 * The camera drop and sideways tilt are done by PlayerMixin / CameraMixin. This screen only exists because
 * Minecraft requires one while dead; it respawns the player after the configured delay.
 */
public class CS16DeathScreen extends Screen {
    private final long start = System.nanoTime();
    private boolean respawnSent;

    public CS16DeathScreen() {
        super(Component.empty());
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override
    protected void init() {
        super.init();
        // Screens release the mouse and show the cursor; hide it again (grabbing the mouse on respawn restores normal mode).
        if (this.minecraft != null) {
            GLFW.glfwSetInputMode(this.minecraft.getWindow().handle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        }
    }

    @Override
    public void tick() {
        super.tick();
        double elapsed = (System.nanoTime() - start) / 1.0e9;
        if (!respawnSent && elapsed >= CS16Config.get().respawnDelay && this.minecraft != null && this.minecraft.player != null) {
            respawnSent = true;
            this.minecraft.player.respawn();
            this.minecraft.gui.setScreen(null);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // Intentionally empty: no blur, no gradient, no text.
    }
}
