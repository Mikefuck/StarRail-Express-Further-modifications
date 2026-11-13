package com.habitrain.core.scene.client.replay;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/**
 * One visible board this frame.
 *
 * @param board  camera-relative board frame (origin at {@code atBottomCenterOf(pos.above())})
 * @param hw     half width of the tray along the viewer's left-right axis
 * @param hd     half depth of the tray (front to back)
 * @param fade   distance fade 0..1
 * @param detail whether fine detail (rivets, name plates, sparks) is submitted
 * @param light  packed light for the textured tray
 */
record ReplayBoardSite(BlockPos pos, Direction facing, double distance, Matrix4f board, Matrix4f table, float scale,
                       float hw, float hd, float fade, boolean detail, int light) {
    /** Tray layout constants in board-frame blocks. */
    static final float TRAY_BOTTOM = 0.10f, BASE_TOP = 0.235f, FRAME_TOP = 0.30f, FRAME = 0.16f;
    static final float MAP_BASE = BASE_TOP + 0.004f;

    Matrix4f at(float x, float y, float z) {
        return new Matrix4f(board).translate(x, y, z);
    }
}
