package com.habitrain.core.scene.model;

/**
 * 单个场景副本的世界空间包围球。
 *
 * <p>运行时使用包围球与场景最远显示距离做粗裁剪，再使用其外接 AABB
 * 参与相机视锥裁剪。包围球只描述几何范围，不持有或复制场景资产。</p>
 */
public record SceneInstanceBounds(
        double centerX,
        double centerY,
        double centerZ,
        double radius
) {
    public SceneInstanceBounds {
        centerX = finiteOrZero(centerX);
        centerY = finiteOrZero(centerY);
        centerZ = finiteOrZero(centerZ);
        radius = Double.isFinite(radius) ? Math.max(0.0, radius) : 0.0;
    }

    public double minX() { return centerX - radius; }
    public double minY() { return centerY - radius; }
    public double minZ() { return centerZ - radius; }
    public double maxX() { return centerX + radius; }
    public double maxY() { return centerY + radius; }
    public double maxZ() { return centerZ + radius; }

    /**
     * 只要包围球仍与最大显示距离球相交，就保留该副本。
     */
    public boolean isWithinDistance(double cameraX, double cameraY, double cameraZ, double maxDistance) {
        if (!Double.isFinite(maxDistance) || maxDistance < 0.0) return false;
        double dx = centerX - cameraX;
        double dy = centerY - cameraY;
        double dz = centerZ - cameraZ;
        double limit = maxDistance + radius;
        return dx * dx + dy * dy + dz * dz <= limit * limit;
    }

    public double distanceSquaredTo(double x, double y, double z) {
        double dx = centerX - x;
        double dy = centerY - y;
        double dz = centerZ - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
