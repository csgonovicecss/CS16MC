package com.example.cs16minecraft.movement;

import com.example.cs16minecraft.config.CS16Config;

/**
 * GoldSrc (pm_shared.c) style movement in GoldSrc units. Velocity is stored in units/second with Y up.
 * Ported behaviours: PM_Friction (incl. edge friction), PM_Accelerate, PM_AirAccelerate (30 u/s cap),
 * PM_Jump (no friction on the jump frame, CS bhop cap), split gravity (half before / half after the move),
 * duck and walk speed factors, velocity clamp.
 * No Minecraft imports: the adapter feeds it input and applies the result.
 */
public final class GoldSrcMovementController {
    public interface GroundProbe {
        /** True if solid ground exists ~34 units below the point offset (in blocks) from the player's feet. */
        boolean hasGroundAt(double dxBlocks, double dzBlocks);
    }

    public static final class Command {
        public float forward, side; // -1..1, side > 0 = right
        public boolean jump, duck, walk;
        public double speedFactor = 1.0; // weapon speed / 250
    }

    public double vx, vy, vz;
    private boolean jumpWasDown;

    // Exposed for HUD / debug
    public volatile String state = "IDLE";
    public volatile boolean ducked, grounded;

    public void preMove(Command cmd, double yawDegrees, boolean onGround, GroundProbe probe, double dt) {
        CS16Config.Movement c = CS16Config.get().movement;

        double maxSpeed = c.maxSpeed * cmd.speedFactor;
        if (cmd.duck) maxSpeed *= c.duckFactor;
        if (cmd.walk) maxSpeed *= c.walkFactor;
        ducked = cmd.duck;

        boolean onGr = onGround;
        boolean jumpEdge = cmd.jump && (!jumpWasDown || c.autoBhop);
        jumpWasDown = cmd.jump;
        String move = "";

        // PM_Jump: leaves the ground this frame, so friction is skipped (the bunnyhop window).
        if (onGr && jumpEdge) {
            if (c.bhopCap) {
                double speed = Math.hypot(vx, vz), cap = c.bhopCapFactor * c.maxSpeed;
                if (speed > cap) {
                    double f = (cap / speed) * 0.8;
                    vx *= f;
                    vz *= f;
                }
            }
            vy = c.jumpSpeed;
            onGr = false;
            move = "JUMP";
        }

        if (onGr) vy = 0; else vy -= c.gravity * dt * 0.5; // PM_AddCorrectGravity

        double yaw = Math.toRadians(yawDegrees);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // right
        double wvx = (fx * cmd.forward + rx * cmd.side) * maxSpeed;
        double wvz = (fz * cmd.forward + rz * cmd.side) * maxSpeed;
        double wishSpeed = Math.hypot(wvx, wvz);
        if (wishSpeed > maxSpeed) {
            wvx *= maxSpeed / wishSpeed;
            wvz *= maxSpeed / wishSpeed;
            wishSpeed = maxSpeed;
        }
        double wdx = 0, wdz = 0;
        if (wishSpeed > 1e-6) { wdx = wvx / wishSpeed; wdz = wvz / wishSpeed; }

        if (onGr) {
            friction(c, dt, probe);
            accelerate(wdx, wdz, wishSpeed, c.accelerate * c.groundAccelMultiplier, dt);
            if (move.isEmpty()) move = cmd.duck ? "DUCK" : cmd.walk ? "WALK" : (Math.hypot(vx, vz) < 1 ? "IDLE" : "RUN");
        } else {
            airAccelerate(wdx, wdz, wishSpeed, c, dt);
            if (move.isEmpty()) move = "AIR";
        }
        clamp(c.maxVelocity);
        grounded = onGround;
        state = move;
    }

    /** PM_FixupGravityVelocity: second half of gravity, applied after collision resolution. */
    public void postMove(boolean onGround, double dt) {
        CS16Config.Movement c = CS16Config.get().movement;
        if (!onGround) vy -= c.gravity * dt * 0.5;
        grounded = onGround;
    }

    private void friction(CS16Config.Movement c, double dt, GroundProbe probe) {
        double speed = Math.hypot(vx, vz);
        if (speed < 0.1) return;
        double friction = c.friction;
        if (probe != null) {
            double nx = vx / speed, nz = vz / speed;
            double ahead = 16.0 / c.unitsPerBlock; // 16 units in front of the player
            if (!probe.hasGroundAt(nx * ahead, nz * ahead)) friction *= c.edgeFriction;
        }
        double control = speed < c.stopSpeed ? c.stopSpeed : speed;
        double drop = control * friction * dt;
        double newSpeed = Math.max(0, speed - drop);
        double k = newSpeed / speed;
        vx *= k;
        vz *= k;
    }

    private void accelerate(double dx, double dz, double wishSpeed, double accel, double dt) {
        double current = vx * dx + vz * dz;
        double add = wishSpeed - current;
        if (add <= 0) return;
        double a = Math.min(accel * dt * wishSpeed, add);
        vx += a * dx;
        vz += a * dz;
    }

    private void airAccelerate(double dx, double dz, double wishSpeed, CS16Config.Movement c, double dt) {
        double wishCapped = Math.min(wishSpeed, c.airSpeedCap);
        double current = vx * dx + vz * dz;
        double add = wishCapped - current;
        if (add <= 0) return;
        double a = Math.min(c.airAccelerate * wishSpeed * dt, add);
        vx += a * dx;
        vz += a * dz;
    }

    private void clamp(double max) {
        vx = Math.max(-max, Math.min(max, vx));
        vy = Math.max(-max, Math.min(max, vy));
        vz = Math.max(-max, Math.min(max, vz));
    }

    public double horizontalSpeed() { return Math.hypot(vx, vz); }
}
