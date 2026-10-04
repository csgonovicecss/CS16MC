package com.example.cs16minecraft.mixin;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The death screens never blur the world behind them (blurriness is also forced to 0 every tick). */
@Mixin(Screen.class)
public abstract class ScreenBlurMixin {
    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void cs16$noBlur(CallbackInfo ci) {
        Object self = this;
        if (self instanceof DeathScreen || self instanceof com.example.cs16minecraft.hud.CS16DeathScreen) ci.cancel();
    }
}
