package com.example.cs16minecraft.movement;

import com.example.cs16minecraft.config.CS16Config;
import com.example.cs16minecraft.input.CS16Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Bridges Minecraft's LocalPlayer and the pure GoldSrc controller.
 * Minecraft ticks at 20 Hz; GoldSrc runs ~100 Hz and its air-strafe / friction behaviour depends on that,
 * so each Minecraft tick is split into several physics substeps with the camera yaw interpolated across them.
 */
public final class PlayerMovementAdapter {
    /** Downward nudge while grounded so Minecraft keeps reporting onGround (a zero-length move never does). */
    private static final double GROUND_STICK_BLOCKS = 0.03;

    public final GoldSrcMovementController controller = new GoldSrcMovementController();
    private final GoldSrcMovementController.Command cmd = new GoldSrcMovementController.Command();
    private Vec3 lastSet = Vec3.ZERO;
    /** Read by PlayerMixin: force Minecraft's crouching pose (smaller hull, lower eyes) while the crouch key is held. */
    public volatile boolean duckWanted;

    public boolean active(LocalPlayer p) {
        return CS16Config.get().movementEnabled
                && !p.getAbilities().flying
                && !p.isInWater() && !p.isInLava()
                && !p.onClimbable()
                && !p.isFallFlying()
                && !p.isPassenger()
                && !p.isSpectator();
    }

    public void tick(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        CS16Config.Movement c = CS16Config.get().movement;
        int n = Math.max(1, c.substeps);
        double dt = c.tickSeconds / n;
        double upb = c.unitsPerBlock;
        double toUnits = upb / c.tickSeconds; // blocks/tick -> units/second

        boolean free = mc.mouseHandler.isMouseGrabbed(); // grabbed == no screen open
        boolean tapped = false;
        while (o.keyJump.consumeClick()) tapped = true; // catches taps shorter than one tick
        cmd.forward = free ? (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0) : 0;
        cmd.side = free ? (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0) : 0;
        cmd.jump = free && (o.keyJump.isDown() || tapped);
        // stays ducked if the pose is still CROUCHING (e.g. released under a low ceiling), like CS
        cmd.duck = (free && CS16Input.CROUCH.isDown()) || p.getPose() == Pose.CROUCHING;
        cmd.walk = free && CS16Input.WALK.isDown();
        cmd.speedFactor = com.example.cs16minecraft.weapon.CS16WeaponManager.INSTANCE.speedFactor();

        GoldSrcMovementController g = controller;

        // Adopt velocity changed by something else (knockback, vanilla jump impulse); otherwise keep our own state.
        Vec3 d = p.getDeltaMovement();
        if (d.distanceToSqr(lastSet) > 1.0e-5) {
            g.vx = d.x * toUnits;
            g.vy = d.y * toUnits;
            g.vz = d.z * toUnits;
        }

        float yawPrev = p.yRotO;
        float dYaw = Mth.wrapDegrees(p.getYRot() - yawPrev);

        for (int i = 0; i < n; i++) {
            double yaw = yawPrev + dYaw * (i + 1) / n;
            boolean grounded = p.onGround();
            g.preMove(cmd, yaw, grounded, (dx, dz) -> groundAt(p, dx, dz, c), dt);

            double sx = g.vx * dt / upb;
            double sz = g.vz * dt / upb;
            double sy = (grounded && g.vy <= 0) ? -GROUND_STICK_BLOCKS : g.vy * dt / upb;

            Vec3 disp = new Vec3(sx, sy, sz);
            p.setDeltaMovement(disp);
            p.move(MoverType.SELF, disp);

            // Minecraft zeroes the axes it collided on; mirror that into our velocity.
            Vec3 after = p.getDeltaMovement();
            if (disp.x != 0 && after.x == 0) g.vx = 0;
            if (disp.z != 0 && after.z == 0) g.vz = 0;
            if (disp.y != 0 && after.y == 0) g.vy = 0;

            g.postMove(p.onGround(), dt);
        }

        lastSet = new Vec3(g.vx / toUnits, g.vy / toUnits, g.vz / toUnits);
        p.setDeltaMovement(lastSet);
    }

    private static boolean groundAt(LocalPlayer p, double dx, double dz, CS16Config.Movement c) {
        double depth = 34.0 / c.unitsPerBlock;
        double x = p.getX() + dx, z = p.getZ() + dz, y = p.getY();
        AABB probe = new AABB(x - 0.05, y - depth, z - 0.05, x + 0.05, y, z + 0.05);
        return !p.level().noCollision(p, probe);
    }
}
