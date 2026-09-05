package com.habitrain.core.scene.model;

import java.util.function.DoubleUnaryOperator;

/**
 * Computes a conservative world-space envelope for every position a scene can occupy and turns
 * that envelope into a client render-distance recommendation.
 *
 * <p>This class is deliberately client-independent so the editor and tests use exactly the same
 * formula. The recommendation is advisory only; it never mutates Minecraft video settings.</p>
 */
public final class SceneVisibilityAdvisor {
    public static final int MIN_CLIENT_CHUNKS = 2;
    public static final double SAFETY_MARGIN_BLOCKS = 16.0;
    public static final double APPROXIMATE_FAR_BLOCKS_PER_CHUNK = 64.0;
    private static final double EPSILON = 1.0e-7;

    private SceneVisibilityAdvisor() {}

    public enum ViewStatus {
        ENOUGH,
        CLIENT_SETTING_LOW,
        SERVER_LIMIT_LOW,
        UNATTAINABLE
    }

    public record AnimationBounds(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ,
                                  boolean valid, boolean finiteEnvelope) {
        public static AnimationBounds invalid() {
            return new AnimationBounds(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, true);
        }

        public double sizeX() { return valid ? Math.max(0.0, maxX - minX) : 0.0; }
        public double sizeY() { return valid ? Math.max(0.0, maxY - minY) : 0.0; }
        public double sizeZ() { return valid ? Math.max(0.0, maxZ - minZ) : 0.0; }

        public double farthestDistanceTo(double x, double y, double z) {
            if (!valid) return 0.0;
            double dx = Math.max(Math.abs(minX - x), Math.abs(maxX - x));
            double dy = Math.max(Math.abs(minY - y), Math.abs(maxY - y));
            double dz = Math.max(Math.abs(minZ - z), Math.abs(maxZ - z));
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public record Advice(AnimationBounds animationBounds,
                         double farthestDistanceBlocks,
                         int rawRequiredChunks,
                         int clientMaximumChunks,
                         int clientConfiguredChunks,
                         int effectiveChunks,
                         boolean attainable,
                         ViewStatus viewStatus,
                         double requiredSceneDistanceBlocks,
                         double configuredSceneDistanceBlocks,
                         boolean sceneDistanceSufficient) {
        public static Advice unavailable(int clientMaximumChunks, int clientConfiguredChunks,
                                         int effectiveChunks, double configuredSceneDistanceBlocks) {
            return new Advice(AnimationBounds.invalid(), 0.0, MIN_CLIENT_CHUNKS,
                    positive(clientMaximumChunks, MIN_CLIENT_CHUNKS),
                    positive(clientConfiguredChunks, MIN_CLIENT_CHUNKS),
                    positive(effectiveChunks, MIN_CLIENT_CHUNKS), false,
                    ViewStatus.UNATTAINABLE, 0.0, configuredSceneDistanceBlocks, true);
        }
    }

    public static AnimationBounds calculateAnimationBounds(SceneProfile profile) {
        Envelope geometry = new Envelope();
        Envelope cullCenters = new Envelope();
        populate(profile, geometry, cullCenters);
        return geometry.toBounds(profile != null && (profile.getMotionMode() == SceneMotionMode.ORBIT
                || profile.getLoop().isEnabled() || profile.getSpeedBlocksPerSecond() <= EPSILON));
    }

    public static Advice advise(SceneProfile profile, double cameraX, double cameraY, double cameraZ,
                                int clientMaximumChunks, int clientConfiguredChunks, int effectiveChunks) {
        int maximum = positive(clientMaximumChunks, MIN_CLIENT_CHUNKS);
        int configured = positive(clientConfiguredChunks, MIN_CLIENT_CHUNKS);
        int effective = positive(effectiveChunks, Math.min(configured, maximum));
        double configuredSceneDistance = profile != null
                ? profile.getRender().getMaxDistanceBlocks() : SceneRenderSettings.DEFAULT_DISTANCE;

        Envelope geometry = new Envelope();
        Envelope cullCenters = new Envelope();
        double cullRadius = populate(profile, geometry, cullCenters);
        AnimationBounds bounds = geometry.toBounds(profile != null && (profile.getMotionMode() == SceneMotionMode.ORBIT
                || profile.getLoop().isEnabled() || profile.getSpeedBlocksPerSecond() <= EPSILON));
        if (!bounds.valid()) {
            return Advice.unavailable(maximum, configured, effective, configuredSceneDistance);
        }

        double farthest = bounds.farthestDistanceTo(cameraX, cameraY, cameraZ);
        int raw = Math.max(MIN_CLIENT_CHUNKS,
                (int) Math.ceil((farthest + SAFETY_MARGIN_BLOCKS) / APPROXIMATE_FAR_BLOCKS_PER_CHUNK));
        boolean attainable = raw <= maximum;
        ViewStatus status;
        if (!attainable) {
            status = ViewStatus.UNATTAINABLE;
        } else if (configured < raw) {
            status = ViewStatus.CLIENT_SETTING_LOW;
        } else if (effective < raw) {
            status = ViewStatus.SERVER_LIMIT_LOW;
        } else {
            status = ViewStatus.ENOUGH;
        }

        double requiredSceneDistance = cullCenters.valid
                ? Math.max(0.0, cullCenters.toBounds(true)
                .farthestDistanceTo(cameraX, cameraY, cameraZ) - cullRadius)
                : 0.0;
        boolean sceneDistanceSufficient = configuredSceneDistance + EPSILON >= requiredSceneDistance;
        return new Advice(bounds, farthest, raw, maximum, configured, effective, attainable, status,
                requiredSceneDistance, configuredSceneDistance, sceneDistanceSufficient);
    }

    /** Returns the exact recommendation boundary used by P12 tests and the settings page. */
    public static int requiredChunksForDistance(double farthestDistanceBlocks) {
        double distance = Double.isFinite(farthestDistanceBlocks)
                ? Math.max(0.0, farthestDistanceBlocks) : 0.0;
        return Math.max(MIN_CLIENT_CHUNKS,
                (int) Math.ceil((distance + SAFETY_MARGIN_BLOCKS) / APPROXIMATE_FAR_BLOCKS_PER_CHUNK));
    }

    /**
     * Populates geometry and culling-center envelopes. Returns the radius subtracted from orbit
     * center distance when checking the scene's own maxDistanceBlocks limit.
     */
    private static double populate(SceneProfile profile, Envelope geometry, Envelope cullCenters) {
        if (profile == null || profile.getSourceBounds().isEmpty()) return 0.0;
        if (profile.getMotionMode() == SceneMotionMode.ORBIT) {
            return populateOrbit(profile, geometry, cullCenters);
        }
        populateLinear(profile, geometry, cullCenters);
        return 0.0; // LINEAR runtime currently culls by the translated scene origin.
    }

    private static void populateLinear(SceneProfile profile, Envelope geometry, Envelope cullCenters) {
        double[] origin = profile.getDisplayOrigin();
        double[] direction = profile.getDirection();
        double start;
        double end;
        if (profile.getLoop().isEnabled()) {
            double distance = SceneMotionMath.effectiveLoopDistance(
                    profile.getSourceBounds(), direction, profile.getLoop());
            start = -distance;
            end = distance;
        } else {
            start = profile.getPhaseOffsetBlocks();
            double radius = modelRadius(profile.getSourceBounds());
            double travel = profile.getSpeedBlocksPerSecond() > EPSILON
                    ? profile.getRender().getMaxDistanceBlocks() + radius : 0.0;
            end = start + travel;
        }

        cullCenters.include(origin[0] + direction[0] * start,
                origin[1] + direction[1] * start, origin[2] + direction[2] * start);
        cullCenters.include(origin[0] + direction[0] * end,
                origin[1] + direction[1] * end, origin[2] + direction[2] * end);

        forEachFixedCorner(profile, corner -> {
            geometry.include(origin[0] + corner[0] + direction[0] * start,
                    origin[1] + corner[1] + direction[1] * start,
                    origin[2] + corner[2] + direction[2] * start);
            geometry.include(origin[0] + corner[0] + direction[0] * end,
                    origin[1] + corner[1] + direction[1] * end,
                    origin[2] + corner[2] + direction[2] * end);
        });
    }

    private static double populateOrbit(SceneProfile profile, Envelope geometry, Envelope cullCenters) {
        SceneOrbitSettings orbit = profile.getOrbit();
        int count = Math.max(SceneOrbitSettings.MIN_INSTANCES,
                Math.min(SceneOrbitSettings.MAX_INSTANCES, orbit.getInstanceCount()));
        double sign = orbit.isClockwise() ? 1.0 : -1.0;
        double sweep = orbit.getAngularSpeedDegreesPerSecond() > EPSILON
                ? Math.max(0.0, Math.min(360.0, orbit.getSweepDegrees())) : 0.0;
        boolean bobActive = orbit.getBobCyclesPerSecond() > EPSILON;
        double radialExpansion = bobActive ? orbit.getRadialBobAmplitudeBlocks() : 0.0;
        double verticalExpansion = bobActive ? orbit.getVerticalBobAmplitudeBlocks() : 0.0;
        double modelRadius = modelRadius(profile.getSourceBounds());
        double[] fixedCenter = SceneOrbitMath.calculateFixedLocalCenter(
                profile.getSourceBounds(), profile.getPivotLocal(), profile.getRotationDegrees());

        for (int instance = 0; instance < count; instance++) {
            double offset = SceneOrbitMath.instanceOffsetAngle(instance, count,
                    orbit.getInstanceSpreadDegrees());
            double startDelta = sign * offset;
            double endDelta = sign * (offset + sweep);
            includeOrbitPointRange(profile, fixedCenter, startDelta, endDelta,
                    radialExpansion, verticalExpansion, cullCenters);
            final double rangeStart = startDelta;
            final double rangeEnd = endDelta;
            forEachFixedCorner(profile, corner -> includeOrbitPointRange(profile, corner,
                    rangeStart, rangeEnd, radialExpansion, verticalExpansion, geometry));
        }
        return modelRadius;
    }

    private static void includeOrbitPointRange(SceneProfile profile, double[] fixedPoint,
                                               double startDelta, double endDelta,
                                               double radialExpansion, double verticalExpansion,
                                               Envelope envelope) {
        double xExpansion = profile.getOrbit().getAxis() == SceneOrbitAxis.X ? 0.0 : radialExpansion;
        double yExpansion = (profile.getOrbit().getAxis() == SceneOrbitAxis.Y ? 0.0 : radialExpansion)
                + verticalExpansion;
        double zExpansion = profile.getOrbit().getAxis() == SceneOrbitAxis.Z ? 0.0 : radialExpansion;
        includeFunctionRange(envelope, 0, delta -> orbitPoint(profile, fixedPoint, delta)[0],
                startDelta, endDelta, xExpansion);
        includeFunctionRange(envelope, 1, delta -> orbitPoint(profile, fixedPoint, delta)[1],
                startDelta, endDelta, yExpansion);
        includeFunctionRange(envelope, 2, delta -> orbitPoint(profile, fixedPoint, delta)[2],
                startDelta, endDelta, zExpansion);
    }

    /** Matches SceneRenderRuntime's T(instance) * Rorbit * F matrix order. */
    private static double[] orbitPoint(SceneProfile profile, double[] fixedPoint, double deltaDegrees) {
        SceneOrbitSettings orbit = profile.getOrbit();
        SceneOrbitAxis axis = orbit.getAxis();
        double[] axisVector = SceneOrbitMath.unitAxis(axis);
        double[] center = SceneOrbitMath.resolveOrbitCenter(profile);
        double[] origin = profile.getDisplayOrigin();
        double dx = origin[0] - center[0];
        double dy = origin[1] - center[1];
        double dz = origin[2] - center[2];
        double height = dx * axisVector[0] + dy * axisVector[1] + dz * axisVector[2];
        double planarX = dx - axisVector[0] * height;
        double planarY = dy - axisVector[1] * height;
        double planarZ = dz - axisVector[2] * height;
        double radius = Math.sqrt(planarX * planarX + planarY * planarY + planarZ * planarZ);
        double theta0 = orbit.getCenterMode() == SceneOrbitCenterMode.MODEL_CENTER
                ? SceneOrbitMath.angleFromVector(axis, new double[]{planarX, planarY, planarZ})
                : orbit.getStartAngleDegrees();
        double[] radial = SceneOrbitMath.unitRadiusVector(axis, theta0 + deltaDegrees);
        double[] point = orbit.isRotateModelWithOrbit()
                ? SceneOrbitMath.rotateAroundAxis(axis, fixedPoint, deltaDegrees)
                : fixedPoint;
        return new double[]{
                center[0] + axisVector[0] * height + radial[0] * radius + point[0],
                center[1] + axisVector[1] * height + radial[1] * radius + point[1],
                center[2] + axisVector[2] * height + radial[2] * radius + point[2]
        };
    }

    private static void includeFunctionRange(Envelope envelope, int axis, DoubleUnaryOperator function,
                                             double startDegrees, double endDegrees, double expansion) {
        double low = Math.min(startDegrees, endDegrees);
        double high = Math.max(startDegrees, endDegrees);
        double f0 = function.applyAsDouble(0.0);
        double f90 = function.applyAsDouble(90.0);
        double f180 = function.applyAsDouble(180.0);
        double constant = (f0 + f180) * 0.5;
        double cosine = (f0 - f180) * 0.5;
        double sine = f90 - constant;
        double amplitude = Math.hypot(cosine, sine);

        double min;
        double max;
        if (high - low >= 360.0 - EPSILON) {
            min = constant - amplitude;
            max = constant + amplitude;
        } else {
            double startValue = function.applyAsDouble(low);
            double endValue = function.applyAsDouble(high);
            min = Math.min(startValue, endValue);
            max = Math.max(startValue, endValue);
            if (amplitude > EPSILON) {
                double maximumAngle = Math.toDegrees(Math.atan2(sine, cosine));
                for (double base : new double[]{maximumAngle, maximumAngle + 180.0}) {
                    long first = (long) Math.ceil((low - base) / 360.0 - EPSILON);
                    long last = (long) Math.floor((high - base) / 360.0 + EPSILON);
                    for (long turn = first; turn <= last; turn++) {
                        double value = function.applyAsDouble(base + turn * 360.0);
                        min = Math.min(min, value);
                        max = Math.max(max, value);
                    }
                }
            }
        }
        envelope.includeAxis(axis, min - expansion, max + expansion);
    }

    private static void forEachFixedCorner(SceneProfile profile, CornerConsumer consumer) {
        SceneBounds bounds = profile.getSourceBounds();
        double[] pivot = profile.getPivotLocal();
        for (int ix = 0; ix <= 1; ix++) {
            for (int iy = 0; iy <= 1; iy++) {
                for (int iz = 0; iz <= 1; iz++) {
                    double[] corner = new double[]{
                            ix == 0 ? 0.0 : bounds.sizeX(),
                            iy == 0 ? 0.0 : bounds.sizeY(),
                            iz == 0 ? 0.0 : bounds.sizeZ()
                    };
                    double[] relative = new double[]{corner[0] - pivot[0], corner[1] - pivot[1], corner[2] - pivot[2]};
                    double[] rotated = SceneOrbitMath.rotateEuler(relative, profile.getRotationDegrees());
                    consumer.accept(new double[]{pivot[0] + rotated[0], pivot[1] + rotated[1], pivot[2] + rotated[2]});
                }
            }
        }
    }

    private static double modelRadius(SceneBounds bounds) {
        double x = bounds != null ? Math.max(0.0, bounds.sizeX()) : 0.0;
        double y = bounds != null ? Math.max(0.0, bounds.sizeY()) : 0.0;
        double z = bounds != null ? Math.max(0.0, bounds.sizeZ()) : 0.0;
        return 0.5 * Math.sqrt(x * x + y * y + z * z);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    @FunctionalInterface
    private interface CornerConsumer {
        void accept(double[] corner);
    }

    private static final class Envelope {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;
        private boolean valid;

        private void include(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            valid = true;
        }

        private void includeAxis(int axis, double min, double max) {
            if (!Double.isFinite(min) || !Double.isFinite(max)) return;
            if (axis == 0) {
                minX = Math.min(minX, min); maxX = Math.max(maxX, max);
            } else if (axis == 1) {
                minY = Math.min(minY, min); maxY = Math.max(maxY, max);
            } else {
                minZ = Math.min(minZ, min); maxZ = Math.max(maxZ, max);
            }
            valid = true;
        }

        private AnimationBounds toBounds(boolean finiteEnvelope) {
            return valid ? new AnimationBounds(minX, minY, minZ, maxX, maxY, maxZ, true, finiteEnvelope)
                    : AnimationBounds.invalid();
        }
    }
}
