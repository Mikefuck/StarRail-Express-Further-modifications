package com.habitrain.core.scene.block;

import net.minecraft.core.Direction;

/** Pure pixel-box rotation used for the board's per-facing outline (kept free of block registration). */
public final class BoardShapeMath {
    private BoardShapeMath() {}

    /**
     * Rotates a north-facing box {@code {x0, y0, z0, x1, y1, z1}} (model pixels, 0..16) about the block
     * centre, matching the blockstate's {@code y} rotation for the given facing.
     */
    public static double[] rotate(double[] box, Direction facing) {
        double x0 = box[0], z0 = box[2], x1 = box[3], z1 = box[5];
        double ax, az, bx, bz;
        switch (facing) {
            case EAST -> { ax = 16 - z0; az = x0; bx = 16 - z1; bz = x1; }
            case SOUTH -> { ax = 16 - x0; az = 16 - z0; bx = 16 - x1; bz = 16 - z1; }
            case WEST -> { ax = z0; az = 16 - x0; bx = z1; bz = 16 - x1; }
            default -> { ax = x0; az = z0; bx = x1; bz = z1; }
        }
        return new double[]{Math.min(ax, bx), box[1], Math.min(az, bz), Math.max(ax, bx), box[4], Math.max(az, bz)};
    }
}
