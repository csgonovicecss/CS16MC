package com.example.cs16minecraft.mixin;

import com.example.cs16minecraft.config.CS16Config;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides Minecraft's first-person arm and held item (the CS viewmodel replaces them). In 26.2 the method is
 * submitHandsWithItems (it was renderHandsWithItems before). Optional: if it is renamed again the hand just
 * stays visible and logs/latest.log shows a mixin warning.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true, require = 0)
    private void cs16$hideHands(CallbackInfo ci) {
        if (CS16Config.get().movementEnabled) ci.cancel();
    }
}
