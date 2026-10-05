package com.example.cs16minecraft.mixin;

import com.example.cs16minecraft.hud.CS16DeathScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps the death screen for the invisible CS one at the moment Minecraft opens it. */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true, require = 0)
    private Screen cs16$swapDeathScreen(Screen screen) {
        return screen instanceof DeathScreen ? new CS16DeathScreen() : screen;
    }
}
