package com.example.cs16minecraft.mixin;

import com.example.cs16minecraft.client.CS16Client;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces vanilla travel() for the local player with the GoldSrc controller. Falls back to vanilla in fluids etc. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void cs16$travel(Vec3 input, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer player && CS16Client.MOVEMENT.active(player)) {
            CS16Client.MOVEMENT.tick(player);
            ci.cancel();
        }
    }
}
