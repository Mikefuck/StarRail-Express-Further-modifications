package com.habitrain.core.scene.model;

/** Shared range definitions and page-friendly validation results for scene profiles. */
public final class SceneProfileValidator {
    private SceneProfileValidator() {}

    public enum LoopDistanceError {
        NONE,
        REQUIRED,
        NOT_A_NUMBER,
        NOT_FINITE,
        OUT_OF_RANGE
    }

    public record LoopDistanceValidation(LoopDistanceError error, double value) {
        public boolean isValid() {
            return error == LoopDistanceError.NONE;
        }
    }

    public static LoopDistanceValidation validateLoopDistance(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new LoopDistanceValidation(LoopDistanceError.REQUIRED, Double.NaN);
        }
        final double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return new LoopDistanceValidation(LoopDistanceError.NOT_A_NUMBER, Double.NaN);
        }
        if (!Double.isFinite(value)) {
            return new LoopDistanceValidation(LoopDistanceError.NOT_FINITE, value);
        }
        if (value < SceneLoopSettings.MIN_DISTANCE || value > SceneLoopSettings.MAX_DISTANCE) {
            return new LoopDistanceValidation(LoopDistanceError.OUT_OF_RANGE, value);
        }
        return new LoopDistanceValidation(LoopDistanceError.NONE, value);
    }

    public enum NumericError {
        NONE,
        REQUIRED,
        NOT_A_NUMBER,
        NOT_FINITE,
        OUT_OF_RANGE
    }

    public record NumericValidation(NumericError error, double value) {
        public boolean isValid() { return error == NumericError.NONE; }
    }

    public record IntValidation(NumericError error, int value) {
        public boolean isValid() { return error == NumericError.NONE; }
    }

    public static NumericValidation validateDouble(String raw, double min, double max) {
        if (raw == null || raw.trim().isEmpty()) {
            return new NumericValidation(NumericError.REQUIRED, Double.NaN);
        }
        final double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return new NumericValidation(NumericError.NOT_A_NUMBER, Double.NaN);
        }
        if (!Double.isFinite(value)) {
            return new NumericValidation(NumericError.NOT_FINITE, value);
        }
        if (value < min || value > max) {
            return new NumericValidation(NumericError.OUT_OF_RANGE, value);
        }
        return new NumericValidation(NumericError.NONE, value);
    }

    public static IntValidation validateInt(String raw, int min, int max) {
        if (raw == null || raw.trim().isEmpty()) {
            return new IntValidation(NumericError.REQUIRED, 0);
        }
        final int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return new IntValidation(NumericError.NOT_A_NUMBER, 0);
        }
        if (value < min || value > max) {
            return new IntValidation(NumericError.OUT_OF_RANGE, value);
        }
        return new IntValidation(NumericError.NONE, value);
    }

    public static NumericValidation validateStartAngle(String raw) {
        return validateDouble(raw, 0.0, 360.0);
    }

    public static NumericValidation validateSweep(String raw) {
        return validateDouble(raw, SceneOrbitSettings.MIN_SWEEP, SceneOrbitSettings.MAX_SWEEP);
    }

    public static NumericValidation validateAngularSpeed(String raw) {
        return validateDouble(raw, SceneOrbitSettings.MIN_SPEED, SceneOrbitSettings.MAX_SPEED);
    }

    public static NumericValidation validateVerticalBob(String raw) {
        return validateDouble(raw, SceneOrbitSettings.MIN_BOB_AMPLITUDE, SceneOrbitSettings.MAX_BOB_AMPLITUDE);
    }

    public static NumericValidation validateRadialBob(String raw) {
        return validateDouble(raw, SceneOrbitSettings.MIN_BOB_AMPLITUDE, SceneOrbitSettings.MAX_BOB_AMPLITUDE);
    }

    public static NumericValidation validateBobCycles(String raw) {
        return validateDouble(raw, SceneOrbitSettings.MIN_BOB_CYCLES, SceneOrbitSettings.MAX_BOB_CYCLES);
    }

    public static IntValidation validateInstanceCount(String raw) {
        return validateInt(raw, SceneOrbitSettings.MIN_INSTANCES, SceneOrbitSettings.MAX_INSTANCES);
    }

    public static NumericValidation validateInstanceSpread(String raw) {
        return validateDouble(raw, SceneOrbitSettings.MIN_SPREAD, SceneOrbitSettings.MAX_SPREAD);
    }

    public static NumericValidation validateCenterCoordinate(String raw) {
        return validateDouble(raw, -30_000_000.0, 30_000_000.0);
    }
}
