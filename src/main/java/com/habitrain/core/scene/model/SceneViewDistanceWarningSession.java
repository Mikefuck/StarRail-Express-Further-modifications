package com.habitrain.core.scene.model;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Client-independent P13 lifecycle state for scene view-distance checks.
 *
 * <p>The session opens its first-check gate one full client tick after SRE reports
 * {@code welcomeTime == 1}. Modes without the standard welcome animation use the fallback gate.
 * Movement is accumulated along the camera path, so several shorter movements still trigger a
 * recalculation after reaching the configured threshold.</p>
 */
public final class SceneViewDistanceWarningSession {
    public static final double MOVEMENT_RECHECK_BLOCKS = 16.0;
    public static final int FALLBACK_DELAY_TICKS = 12 * 20;

    public enum CheckReason {
        NONE,
        INITIAL,
        SCENE_CHANGED,
        DIMENSION_CHANGED,
        MOVED
    }

    public enum WarningKind {
        NONE,
        CLIENT_SETTING_LOW,
        SERVER_LIMIT_LOW
    }

    public record TickInput(double cameraX, double cameraY, double cameraZ,
                            String dimensionKey, String sceneSignature,
                            boolean displayUnblocked) {}

    private final Set<String> warnedMapKeys = new HashSet<>();
    private boolean roundActive;
    private int ticksSinceRoundStart;
    private int welcomeDelayTicks = -1;
    private boolean checkGateOpen;
    private boolean initialCheckDone;
    private String observedDimension = "";
    private String lastCheckedDimension = "";
    private String lastCheckedSceneSignature = "";
    private boolean hasCameraSample;
    private double sampleX;
    private double sampleY;
    private double sampleZ;
    private double accumulatedMovement;

    public void startRound() {
        reset();
        roundActive = true;
    }

    public void reset() {
        roundActive = false;
        ticksSinceRoundStart = 0;
        welcomeDelayTicks = -1;
        checkGateOpen = false;
        initialCheckDone = false;
        observedDimension = "";
        lastCheckedDimension = "";
        lastCheckedSceneSignature = "";
        hasCameraSample = false;
        accumulatedMovement = 0.0;
        warnedMapKeys.clear();
    }

    /** Records SRE's last welcome-animation tick. Repeated callbacks cannot reopen the gate. */
    public void onWelcomeTimer(int welcomeTime) {
        if (roundActive && welcomeTime == 1 && !checkGateOpen && welcomeDelayTicks < 0) {
            welcomeDelayTicks = 1;
        }
    }

    public CheckReason tick(TickInput input) {
        if (!roundActive || input == null) return CheckReason.NONE;
        ticksSinceRoundStart++;

        String dimension = normalized(input.dimensionKey());
        if (!Objects.equals(observedDimension, dimension)) {
            observedDimension = dimension;
            hasCameraSample = false;
            accumulatedMovement = 0.0;
        }
        recordMovement(input.cameraX(), input.cameraY(), input.cameraZ());

        if (!checkGateOpen) {
            if (welcomeDelayTicks >= 0) {
                if (welcomeDelayTicks > 0) {
                    welcomeDelayTicks--;
                    return CheckReason.NONE;
                }
                checkGateOpen = true;
            } else if (ticksSinceRoundStart >= FALLBACK_DELAY_TICKS) {
                if (!input.displayUnblocked()) return CheckReason.NONE;
                checkGateOpen = true;
            } else {
                return CheckReason.NONE;
            }
        }
        if (!input.displayUnblocked()) return CheckReason.NONE;

        String signature = normalized(input.sceneSignature());
        CheckReason reason = CheckReason.NONE;
        if (!initialCheckDone) {
            reason = CheckReason.INITIAL;
        } else if (!Objects.equals(lastCheckedDimension, dimension)) {
            reason = CheckReason.DIMENSION_CHANGED;
        } else if (!Objects.equals(lastCheckedSceneSignature, signature)) {
            reason = CheckReason.SCENE_CHANGED;
        } else if (accumulatedMovement + 1.0e-7 >= MOVEMENT_RECHECK_BLOCKS) {
            reason = CheckReason.MOVED;
        }

        if (reason != CheckReason.NONE) {
            initialCheckDone = true;
            lastCheckedDimension = dimension;
            lastCheckedSceneSignature = signature;
            accumulatedMovement = 0.0;
            if (finite(input.cameraX(), input.cameraY(), input.cameraZ())) {
                hasCameraSample = true;
                sampleX = input.cameraX();
                sampleY = input.cameraY();
                sampleZ = input.cameraZ();
            }
        }
        return reason;
    }

    public boolean hasWarned(String mapKey) {
        return warnedMapKeys.contains(normalizedMapKey(mapKey));
    }

    public void markWarned(String mapKey) {
        if (roundActive) warnedMapKeys.add(normalizedMapKey(mapKey));
    }

    public static WarningKind warningKind(SceneVisibilityAdvisor.Advice advice) {
        if (advice == null || !advice.animationBounds().valid()
                || !advice.sceneDistanceSufficient()) {
            return WarningKind.NONE;
        }
        return switch (advice.viewStatus()) {
            case CLIENT_SETTING_LOW -> WarningKind.CLIENT_SETTING_LOW;
            case SERVER_LIMIT_LOW -> WarningKind.SERVER_LIMIT_LOW;
            case ENOUGH, UNATTAINABLE -> WarningKind.NONE;
        };
    }

    private void recordMovement(double x, double y, double z) {
        if (!finite(x, y, z)) return;
        if (hasCameraSample) {
            double dx = x - sampleX;
            double dy = y - sampleY;
            double dz = z - sampleZ;
            accumulatedMovement += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        hasCameraSample = true;
        sampleX = x;
        sampleY = y;
        sampleZ = z;
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private static String normalized(String value) {
        return value != null ? value : "";
    }

    private static String normalizedMapKey(String mapKey) {
        return mapKey == null || mapKey.isBlank() ? "__default__" : mapKey;
    }
}
