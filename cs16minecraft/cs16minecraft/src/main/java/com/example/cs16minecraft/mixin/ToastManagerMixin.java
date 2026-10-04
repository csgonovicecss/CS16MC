package com.example.cs16minecraft.mixin;

import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No pop-up toasts (advancements / achievements, recipes, tutorials). Optional: skipped if the method is renamed. */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {
    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true, require = 0)
    private void cs16$noToasts(CallbackInfo ci) {
        ci.cancel();
    }
}
