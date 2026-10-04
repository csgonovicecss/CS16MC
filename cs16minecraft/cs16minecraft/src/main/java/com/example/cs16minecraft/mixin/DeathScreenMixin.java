package com.example.cs16minecraft.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DeathScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The vanilla death screen never draws (no red gradient, no "You died!"), even for the frame before it is swapped. */
@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private void cs16$noDraw(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void cs16$noBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ci.cancel();
    }
}
