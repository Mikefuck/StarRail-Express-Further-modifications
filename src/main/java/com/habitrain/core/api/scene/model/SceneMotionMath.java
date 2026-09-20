package com.habitrain.core.api.scene.model;

/** Pure motion/tiling math shared by the editor, runtime and tests. */
public final class SceneMotionMath {
    private static final double EPSILON = 1.0e-8;

    private SceneMotionMath() {}

    /**
     * Separates the value entered by the administrator from the seamless value used at runtime.
     * The configured value must remain stable when the direction or captured bounds change.
     */
    public record LoopDistances(double configuredDistance,
                                double recommendedDistance,
                                double effectiveDistance) {}

    public enum SeamRelation {
        UNKNOWN,
        SEAMLESS,
        OVERLAP,
        GAP
    }

    /**
     * Resolves the configured step used by a pair of scene copies.
     *
     * <p>The captured extent is still exposed as a recommendation, but it must not
     * override an administrator's explicit spacing. This is especially important for
     * vertical motion where a one-block-tall source may intentionally repeat every
     * several blocks.</p>
     */
    public static double seamlessLoopDistance(SceneBounds bounds, double[] direction,
                                              double requestedDistance) {
        return resolveLoopDistances(bounds, direction, requestedDistance).effectiveDistance();
    }

    /**
     * Resolves all loop-distance values without destroying or restricting the administrator's
     * configured value. The recommended distance is the captured extent projected onto the unit
     * movement direction; it remains an editor hint and is only applied when the administrator
     * explicitly chooses it.
     */
    public static LoopDistances resolveLoopDistances(SceneBounds bounds, double[] direction,
                                                     double requestedDistance) {
        double requested = finiteOr(requestedDistance, SceneLoopSettings.DEFAULT_DISTANCE);
        requested = clamp(requested, SceneLoopSettings.MIN_DISTANCE, SceneLoopSettings.MAX_DISTANCE);
        if (bounds == null || bounds.isEmpty() || direction == null || direction.length < 3) {
            return new LoopDistances(requested, requested, requested);
        }

        double dx = finiteOr(direction[0], 0.0);
        double dy = finiteOr(direction[1], 0.0);
        double dz = finiteOr(direction[2], 0.0);
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < EPSILON) return new LoopDistances(requested, requested, requested);

        dx /= length;
        dy /= length;
        dz /= length;
        double projectedExtent = Math.abs(dx) * bounds.sizeX()
                + Math.abs(dy) * bounds.sizeY()
                + Math.abs(dz) * bounds.sizeZ();
        if (!Double.isFinite(projectedExtent) || projectedExtent < EPSILON) {
            return new LoopDistances(requested, requested, requested);
        }

        double recommended = clamp(projectedExtent,
                SceneLoopSettings.MIN_DISTANCE, SceneLoopSettings.MAX_DISTANCE);
        return new LoopDistances(requested, recommended, requested);
    }

    /** Resolves AUTO/CUSTOM without overwriting the stored last custom value. */
    public static LoopDistances resolveLoopDistances(SceneBounds bounds, double[] direction,
                                                     SceneLoopSettings settings) {
        SceneLoopSettings loop = settings != null ? settings : SceneLoopSettings.createDefault();
        LoopDistances custom = resolveLoopDistances(bounds, direction, loop.getDistanceBlocks());
        double recommended = hasRecommendationContext(bounds, direction)
                ? custom.recommendedDistance() : SceneLoopSettings.DEFAULT_DISTANCE;
        double effective = loop.getDistanceMode() == SceneLoopDistanceMode.AUTO
                ? recommended : custom.configuredDistance();
        return new LoopDistances(custom.configuredDistance(), recommended, effective);
    }

    public static double effectiveLoopDistance(SceneBounds bounds, double[] direction,
                                               SceneLoopSettings settings) {
        return resolveLoopDistances(bounds, direction, settings).effectiveDistance();
    }

    public static SeamRelation seamRelation(SceneBounds bounds, double[] direction,
                                            SceneLoopSettings settings) {
        if (bounds == null || bounds.isEmpty()) return SeamRelation.UNKNOWN;
        LoopDistances distances = resolveLoopDistances(bounds, direction, settings);
        double delta = distances.effectiveDistance() - distances.recommendedDistance();
        if (Math.abs(delta) <= 1.0e-6) return SeamRelation.SEAMLESS;
        return delta < 0.0 ? SeamRelation.OVERLAP : SeamRelation.GAP;
    }

    private static boolean hasRecommendationContext(SceneBounds bounds, double[] direction) {
        if (bounds == null || bounds.isEmpty() || direction == null || direction.length < 3) return false;
        double dx = finiteOr(direction[0], 0.0);
        double dy = finiteOr(direction[1], 0.0);
        double dz = finiteOr(direction[2], 0.0);
        return dx * dx + dy * dy + dz * dz >= EPSILON * EPSILON;
    }

    /** Looping profiles wrap; single-pass profiles keep moving instead of teleporting back. */
    public static double phase(boolean loopEnabled, double speed, double elapsedSeconds,
                               double phaseOffset, double loopDistance) {
        double unwrapped = finiteOr(speed, 0.0) * Math.max(0.0, finiteOr(elapsedSeconds, 0.0))
                + finiteOr(phaseOffset, 0.0);
        if (!loopEnabled) return unwrapped;
        double distance = finiteOr(loopDistance, 0.0);
        if (distance <= 0.0) return 0.0;
        return unwrapped - Math.floor(unwrapped / distance) * distance;
    }

    /**
     * 直线运动副本在时刻 t 的世界空间包围球，供运行时裁剪使用。
     *
     * <p>球心与渲染矩阵同源：{@code buildSceneModelMatrix} 施加的是
     * {@code T(位置) · [T(pivot) · R · T(-pivot)]}，局部几何中心 {@code L = (size/2)} 的像正是
     * {@link SceneOrbitMath#calculateFixedLocalCenter} 给出的 {@code F(L)}。旋转是刚体变换，
     * 因此以包围盒半对角线为半径的球在旋转前后不变——这正是环绕模式已经在用的口径，
     * 于是两边的距离判定与视锥判定可以直接复用同一套代码。</p>
     *
     * @param offsetX 运动相位与循环回绕造成的世界位移（{@code motion + loopOffset}）
     * @return 世界空间包围球；几何为空时退化为原点半径 0 的球
     */
    public static SceneInstanceBounds calculateLinearInstanceBounds(SceneProfile profile,
                                                                    double offsetX, double offsetY, double offsetZ) {
        if (profile == null) return new SceneInstanceBounds(0.0, 0.0, 0.0, 0.0);
        SceneBounds bounds = profile.getSourceBounds();
        double sx = bounds != null ? Math.max(0.0, bounds.sizeX()) : 0.0;
        double sy = bounds != null ? Math.max(0.0, bounds.sizeY()) : 0.0;
        double sz = bounds != null ? Math.max(0.0, bounds.sizeZ()) : 0.0;
        double radius = 0.5 * Math.sqrt(sx * sx + sy * sy + sz * sz);

        double[] localCenter = SceneOrbitMath.calculateFixedLocalCenter(
                bounds, profile.pivotLocalRaw(), profile.getRotationDegrees());
        double[] origin = profile.displayOriginRaw();
        double ox = origin != null && origin.length >= 1 ? finiteOr(origin[0], 0.0) : 0.0;
        double oy = origin != null && origin.length >= 2 ? finiteOr(origin[1], 0.0) : 0.0;
        double oz = origin != null && origin.length >= 3 ? finiteOr(origin[2], 0.0) : 0.0;
        return new SceneInstanceBounds(
                ox + offsetX + localCenter[0],
                oy + offsetY + localCenter[1],
                oz + offsetZ + localCenter[2],
                radius);
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
