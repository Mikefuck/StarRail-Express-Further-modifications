package com.habitrain.core.game.sre;

/** Identifies the world area that owns the current custom-task block snapshot. */
record CustomTaskBlockScanKey(
        String dimensionId,
        String mapName,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ) {

    CustomTaskBlockScanKey {
        dimensionId = dimensionId == null ? "" : dimensionId;
        mapName = mapName == null ? "" : mapName;
    }
}
