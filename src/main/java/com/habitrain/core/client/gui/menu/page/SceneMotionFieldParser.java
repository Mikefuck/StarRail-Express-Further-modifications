package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.api.scene.model.SceneProfileValidator;

import java.util.Locale;

/**
 * Parses and formats values shared by the scene motion editor fields.
 * Keeping this policy outside the screen makes input filters and preview/save
 * conversions behave identically without coupling them to widget state.
 */
final class SceneMotionFieldParser {
    private SceneMotionFieldParser() {}

    static boolean isPotentialDecimal(String value) {
        return value == null || value.isEmpty()
                || value.matches("[-+]?((\\d+(\\.\\d*)?)|(\\.\\d*))?");
    }

    static boolean isPotentialInteger(String value) {
        return value == null || value.isEmpty() || value.matches("[+]?\\d*");
    }

    static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static double[] direction(String x, String y, String z, double[] fallback) {
        double directionX = parseDouble(x, fallback[0]);
        double directionY = parseDouble(y, fallback[1]);
        double directionZ = parseDouble(z, fallback[2]);
        double length = Math.sqrt(directionX * directionX
                + directionY * directionY + directionZ * directionZ);
        if (!Double.isFinite(length) || length < 1.0e-4) {
            return new double[]{-1.0, 0.0, 0.0};
        }
        return new double[]{directionX / length, directionY / length, directionZ / length};
    }

    static String loopDistanceErrorKey(SceneProfileValidator.LoopDistanceError error) {
        return switch (error) {
            case REQUIRED -> "screen.habitrain_core.scene_motion.loop_error_required";
            case NOT_A_NUMBER -> "screen.habitrain_core.scene_motion.loop_error_number";
            case NOT_FINITE -> "screen.habitrain_core.scene_motion.loop_error_finite";
            case OUT_OF_RANGE -> "screen.habitrain_core.scene_motion.loop_error_range";
            case NONE -> "screen.habitrain_core.scene_motion.loop_error_number";
        };
    }

    static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String formatDirectionInput(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0e-8) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return format(value);
    }
}
