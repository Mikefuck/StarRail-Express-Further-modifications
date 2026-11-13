package com.habitrain.core.scene.replay;

import com.habitrain.core.scene.block.ReplayBoardSettings;
import org.joml.Matrix4f;

/** Shared transform for the map, projected tracks, tray and honors. The tabletop is the anchor. */
public final class ReplayBoardPlacement {
    private ReplayBoardPlacement() {}

    public static Matrix4f displayMatrix(Matrix4f table, ReplayBoardSettings settings, float mapBase) {
        return new Matrix4f(table).translate(settings.offsetX(), settings.height(), settings.offsetZ())
                .rotateY((float) Math.toRadians(settings.rotation())).scale(settings.scale()).translate(0, -mapBase, 0);
    }
}
