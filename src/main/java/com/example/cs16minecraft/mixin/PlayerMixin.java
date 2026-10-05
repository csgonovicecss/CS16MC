package com.example.cs16minecraft.mixin;

import com.example.cs16minecraft.client.CS16Client;
import com.example.cs16minecraft.config.CS16Config;
import com.example.cs16minecraft.movement.CS16Dimensions;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CS hull + crouching. "require = 0": if a target is renamed in a future Minecraft version the game still
 * launches and only crouching / the hull change is skipped (check latest.log for the mixin warning).
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true, require = 0)
    private void cs16$dimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (!CS16Config.get().movementEnabled) return;
        if (pose == Pose.STANDING) cir.setReturnValue(CS16Dimensions.standing());
        else if (pose == Pose.CROUCHING) cir.setReturnValue(CS16Dimensions.ducked());
        else if (pose == Pose.DYING) cir.setReturnValue(CS16Dimensions.dead());
    }

    @Inject(method = "updatePlayerPose", at = @At("TAIL"), require = 0)
    private void cs16$duck(CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer p
                && CS16Client.MOVEMENT.duckWanted
                && p.getPose() == Pose.STANDING
                && !p.getAbilities().flying) {
            p.setPose(Pose.CROUCHING);
        }
    }
}
