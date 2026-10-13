package com.habitrain.core.game.sre.task;

/** Identifies the world area that owns the current custom-task block snapshot. */
record CustomTaskBlockScanKey(
        String dimensionId,
        String mapName,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        String worldId,
        String definitionFingerprint,
        String mapRevision) {

    CustomTaskBlockScanKey(String dimensionId, String mapName,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this(dimensionId, mapName, minX, minY, minZ, maxX, maxY, maxZ, "", "", "");
    }

    CustomTaskBlockScanKey {
        dimensionId = dimensionId == null ? "" : dimensionId;
        mapName = mapName == null ? "" : mapName;
    }
}
