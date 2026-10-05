package com.example.cs16minecraft.mixin;

import com.example.cs16minecraft.client.CS16Death;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Adds the dead-player roll to the camera's view quaternion. Optional: skipped if the target changes. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @ModifyArg(method = "setRotation(FF)V",
            at = @At(value = "INVOKE", target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;"),
            index = 2, require = 0)
    private float cs16$deathRoll(float roll) {
        return roll + CS16Death.rollRadians();
    }
}
