package com.example.cs16minecraft.movement;

import net.minecraft.world.entity.EntityDimensions;

/** GoldSrc player hulls: 32x72 standing, 32x36 ducked, at 40 units per block. Eye height 64 / ~35 units. */
public final class CS16Dimensions {
    private CS16Dimensions() {}

    public static EntityDimensions standing() { return EntityDimensions.scalable(0.8F, 1.8F).withEyeHeight(1.6F); }

    /** Dead player: the camera drops to a few units above the floor (GoldSrc DEAD_VIEWHEIGHT). */
    private static boolean loggedDead;

    public static EntityDimensions dead() {
        if (!loggedDead) { loggedDead = true; com.example.cs16minecraft.CS16Minecraft.LOGGER.info("CS16: death camera hull active"); }
        return EntityDimensions.fixed(0.5F, 0.4F).withEyeHeight(0.2F);
    }

    public static EntityDimensions ducked() { return EntityDimensions.scalable(0.8F, 0.9F).withEyeHeight(0.875F); }
}
