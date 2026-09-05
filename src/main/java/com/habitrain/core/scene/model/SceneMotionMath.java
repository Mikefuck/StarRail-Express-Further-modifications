package com.habitrain.core.scene.model;

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

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
