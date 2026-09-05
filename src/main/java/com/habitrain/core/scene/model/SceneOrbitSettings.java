package com.habitrain.core.scene.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Objects;

/**
 * 围绕中心旋转运动配置。
 */
public final class SceneOrbitSettings {
    public static final double DEFAULT_SPEED = 30.0;
    public static final double DEFAULT_SWEEP = 360.0;
    public static final double DEFAULT_SPREAD = 360.0;

    public static final double MIN_SPEED = 0.0;
    public static final double MAX_SPEED = 720.0;
    public static final double MIN_SWEEP = 0.0;
    public static final double MAX_SWEEP = 360.0;
    public static final double MIN_BOB_AMPLITUDE = 0.0;
    public static final double MAX_BOB_AMPLITUDE = 64.0;
    public static final double MIN_BOB_CYCLES = 0.0;
    public static final double MAX_BOB_CYCLES = 10.0;
    public static final int MIN_INSTANCES = 1;
    public static final int MAX_INSTANCES = 16;
    public static final double MIN_SPREAD = 0.0;
    public static final double MAX_SPREAD = 360.0;

    private SceneOrbitCenterMode centerMode;
    private double[] centerWorld; // [x, y, z]
    private SceneOrbitAxis axis;
    private double startAngleDegrees;
    private double sweepDegrees;
    private boolean clockwise;
    private double angularSpeedDegreesPerSecond;
    private double verticalBobAmplitudeBlocks;
    private double radialBobAmplitudeBlocks;
    private double bobCyclesPerSecond;
    private int instanceCount;
    private double instanceSpreadDegrees;
    private boolean rotateModelWithOrbit;

    public SceneOrbitSettings() {
        this.centerMode = SceneOrbitCenterMode.WORLD_BLOCK;
        this.centerWorld = new double[]{0.0, 0.0, 0.0};
        this.axis = SceneOrbitAxis.Y;
        this.startAngleDegrees = 0.0;
        this.sweepDegrees = DEFAULT_SWEEP;
        this.clockwise = true;
        this.angularSpeedDegreesPerSecond = DEFAULT_SPEED;
        this.verticalBobAmplitudeBlocks = 0.0;
        this.radialBobAmplitudeBlocks = 0.0;
        this.bobCyclesPerSecond = 0.0;
        this.instanceCount = 1;
        this.instanceSpreadDegrees = DEFAULT_SPREAD;
        this.rotateModelWithOrbit = true;
    }

    public static SceneOrbitSettings createDefault() {
        return new SceneOrbitSettings();
    }

    public SceneOrbitSettings copy() {
        SceneOrbitSettings c = new SceneOrbitSettings();
        c.centerMode = this.centerMode;
        c.centerWorld = Arrays.copyOf(this.centerWorld, 3);
        c.axis = this.axis;
        c.startAngleDegrees = this.startAngleDegrees;
        c.sweepDegrees = this.sweepDegrees;
        c.clockwise = this.clockwise;
        c.angularSpeedDegreesPerSecond = this.angularSpeedDegreesPerSecond;
        c.verticalBobAmplitudeBlocks = this.verticalBobAmplitudeBlocks;
        c.radialBobAmplitudeBlocks = this.radialBobAmplitudeBlocks;
        c.bobCyclesPerSecond = this.bobCyclesPerSecond;
        c.instanceCount = this.instanceCount;
        c.instanceSpreadDegrees = this.instanceSpreadDegrees;
        c.rotateModelWithOrbit = this.rotateModelWithOrbit;
        return c;
    }

    // --- Getters & Setters ---

    public SceneOrbitCenterMode getCenterMode() {
        return centerMode != null ? centerMode : SceneOrbitCenterMode.WORLD_BLOCK;
    }

    public void setCenterMode(SceneOrbitCenterMode centerMode) {
        this.centerMode = centerMode != null ? centerMode : SceneOrbitCenterMode.WORLD_BLOCK;
        if (this.centerMode == SceneOrbitCenterMode.MODEL_CENTER) {
            this.rotateModelWithOrbit = true;
        }
    }

    public double[] getCenterWorld() {
        return centerWorld;
    }

    public void setCenterWorld(double x, double y, double z) {
        this.centerWorld = new double[]{
                validateFinite(x, 0.0),
                validateFinite(y, 0.0),
                validateFinite(z, 0.0)
        };
    }

    public SceneOrbitAxis getAxis() {
        return axis != null ? axis : SceneOrbitAxis.Y;
    }

    public void setAxis(SceneOrbitAxis axis) {
        this.axis = axis != null ? axis : SceneOrbitAxis.Y;
    }

    public double getStartAngleDegrees() {
        return startAngleDegrees;
    }

    public void setStartAngleDegrees(double angle) {
        double val = validateFinite(angle, 0.0);
        val = val % 360.0;
        if (val < 0.0) val += 360.0;
        this.startAngleDegrees = val;
    }

    public double getSweepDegrees() {
        return sweepDegrees;
    }

    public void setSweepDegrees(double sweep) {
        double s = validateFinite(sweep, DEFAULT_SWEEP);
        this.sweepDegrees = Math.max(MIN_SWEEP, Math.min(MAX_SWEEP, s));
    }

    public boolean isClockwise() {
        return clockwise;
    }

    public void setClockwise(boolean clockwise) {
        this.clockwise = clockwise;
    }

    public double getAngularSpeedDegreesPerSecond() {
        return angularSpeedDegreesPerSecond;
    }

    public void setAngularSpeedDegreesPerSecond(double speed) {
        double s = validateFinite(speed, DEFAULT_SPEED);
        this.angularSpeedDegreesPerSecond = Math.max(MIN_SPEED, Math.min(MAX_SPEED, s));
    }

    public double getVerticalBobAmplitudeBlocks() {
        return verticalBobAmplitudeBlocks;
    }

    public void setVerticalBobAmplitudeBlocks(double amp) {
        double a = validateFinite(amp, 0.0);
        this.verticalBobAmplitudeBlocks = Math.max(MIN_BOB_AMPLITUDE, Math.min(MAX_BOB_AMPLITUDE, a));
    }

    public double getRadialBobAmplitudeBlocks() {
        return radialBobAmplitudeBlocks;
    }

    public void setRadialBobAmplitudeBlocks(double amp) {
        double a = validateFinite(amp, 0.0);
        this.radialBobAmplitudeBlocks = Math.max(MIN_BOB_AMPLITUDE, Math.min(MAX_BOB_AMPLITUDE, a));
    }

    public double getBobCyclesPerSecond() {
        return bobCyclesPerSecond;
    }

    public void setBobCyclesPerSecond(double cycles) {
        double c = validateFinite(cycles, 0.0);
        this.bobCyclesPerSecond = Math.max(MIN_BOB_CYCLES, Math.min(MAX_BOB_CYCLES, c));
    }

    public int getInstanceCount() {
        return instanceCount;
    }

    public void setInstanceCount(int count) {
        this.instanceCount = Math.max(MIN_INSTANCES, Math.min(MAX_INSTANCES, count));
    }

    public double getInstanceSpreadDegrees() {
        return instanceSpreadDegrees;
    }

    public void setInstanceSpreadDegrees(double spread) {
        double s = validateFinite(spread, DEFAULT_SPREAD);
        this.instanceSpreadDegrees = Math.max(MIN_SPREAD, Math.min(MAX_SPREAD, s));
    }

    public boolean isRotateModelWithOrbit() {
        return centerMode == SceneOrbitCenterMode.MODEL_CENTER || rotateModelWithOrbit;
    }

    public void setRotateModelWithOrbit(boolean rotate) {
        if (centerMode == SceneOrbitCenterMode.MODEL_CENTER) {
            this.rotateModelWithOrbit = true;
        } else {
            this.rotateModelWithOrbit = rotate;
        }
    }

    private static double validateFinite(double val, double fallback) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return fallback;
        return val;
    }

    // --- JSON Codec ---

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("centerMode", getCenterMode().name());

        JsonArray arr = new JsonArray();
        arr.add(centerWorld[0]);
        arr.add(centerWorld[1]);
        arr.add(centerWorld[2]);
        json.add("centerWorld", arr);

        json.addProperty("axis", getAxis().name());
        json.addProperty("startAngleDegrees", startAngleDegrees);
        json.addProperty("sweepDegrees", sweepDegrees);
        json.addProperty("clockwise", clockwise);
        json.addProperty("angularSpeedDegreesPerSecond", angularSpeedDegreesPerSecond);
        json.addProperty("verticalBobAmplitudeBlocks", verticalBobAmplitudeBlocks);
        json.addProperty("radialBobAmplitudeBlocks", radialBobAmplitudeBlocks);
        json.addProperty("bobCyclesPerSecond", bobCyclesPerSecond);
        json.addProperty("instanceCount", instanceCount);
        json.addProperty("instanceSpreadDegrees", instanceSpreadDegrees);
        json.addProperty("rotateModelWithOrbit", isRotateModelWithOrbit());
        return json;
    }

    public static SceneOrbitSettings fromJson(JsonObject json) {
        SceneOrbitSettings s = new SceneOrbitSettings();
        if (json == null) return s;

        if (json.has("centerMode")) {
            try {
                s.setCenterMode(SceneOrbitCenterMode.valueOf(json.get("centerMode").getAsString().trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                s.setCenterMode(SceneOrbitCenterMode.WORLD_BLOCK);
            }
        }
        if (json.has("centerWorld") && json.get("centerWorld").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("centerWorld");
            if (arr.size() >= 3) {
                s.setCenterWorld(arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble());
            }
        }
        if (json.has("axis")) {
            try {
                s.setAxis(SceneOrbitAxis.valueOf(json.get("axis").getAsString().trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                s.setAxis(SceneOrbitAxis.Y);
            }
        }
        if (json.has("startAngleDegrees")) {
            s.setStartAngleDegrees(json.get("startAngleDegrees").getAsDouble());
        }
        if (json.has("sweepDegrees")) {
            s.setSweepDegrees(json.get("sweepDegrees").getAsDouble());
        }
        if (json.has("clockwise")) {
            s.setClockwise(json.get("clockwise").getAsBoolean());
        }
        if (json.has("angularSpeedDegreesPerSecond")) {
            s.setAngularSpeedDegreesPerSecond(json.get("angularSpeedDegreesPerSecond").getAsDouble());
        }
        if (json.has("verticalBobAmplitudeBlocks")) {
            s.setVerticalBobAmplitudeBlocks(json.get("verticalBobAmplitudeBlocks").getAsDouble());
        }
        if (json.has("radialBobAmplitudeBlocks")) {
            s.setRadialBobAmplitudeBlocks(json.get("radialBobAmplitudeBlocks").getAsDouble());
        }
        if (json.has("bobCyclesPerSecond")) {
            s.setBobCyclesPerSecond(json.get("bobCyclesPerSecond").getAsDouble());
        }
        if (json.has("instanceCount")) {
            s.setInstanceCount(json.get("instanceCount").getAsInt());
        }
        if (json.has("instanceSpreadDegrees")) {
            s.setInstanceSpreadDegrees(json.get("instanceSpreadDegrees").getAsDouble());
        }
        if (json.has("rotateModelWithOrbit")) {
            s.setRotateModelWithOrbit(json.get("rotateModelWithOrbit").getAsBoolean());
        }
        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneOrbitSettings that)) return false;
        return Double.compare(startAngleDegrees, that.startAngleDegrees) == 0 &&
                Double.compare(sweepDegrees, that.sweepDegrees) == 0 &&
                clockwise == that.clockwise &&
                Double.compare(angularSpeedDegreesPerSecond, that.angularSpeedDegreesPerSecond) == 0 &&
                Double.compare(verticalBobAmplitudeBlocks, that.verticalBobAmplitudeBlocks) == 0 &&
                Double.compare(radialBobAmplitudeBlocks, that.radialBobAmplitudeBlocks) == 0 &&
                Double.compare(bobCyclesPerSecond, that.bobCyclesPerSecond) == 0 &&
                instanceCount == that.instanceCount &&
                Double.compare(instanceSpreadDegrees, that.instanceSpreadDegrees) == 0 &&
                rotateModelWithOrbit == that.rotateModelWithOrbit &&
                centerMode == that.centerMode &&
                axis == that.axis &&
                Arrays.equals(centerWorld, that.centerWorld);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(centerMode, axis, startAngleDegrees, sweepDegrees, clockwise,
                angularSpeedDegreesPerSecond, verticalBobAmplitudeBlocks, radialBobAmplitudeBlocks,
                bobCyclesPerSecond, instanceCount, instanceSpreadDegrees, rotateModelWithOrbit);
        result = 31 * result + Arrays.hashCode(centerWorld);
        return result;
    }

    @Override
    public String toString() {
        return "SceneOrbitSettings{" +
                "centerMode=" + centerMode +
                ", centerWorld=" + Arrays.toString(centerWorld) +
                ", axis=" + axis +
                ", startAngleDegrees=" + startAngleDegrees +
                ", sweepDegrees=" + sweepDegrees +
                ", clockwise=" + clockwise +
                ", angularSpeedDegreesPerSecond=" + angularSpeedDegreesPerSecond +
                ", verticalBobAmplitudeBlocks=" + verticalBobAmplitudeBlocks +
                ", radialBobAmplitudeBlocks=" + radialBobAmplitudeBlocks +
                ", bobCyclesPerSecond=" + bobCyclesPerSecond +
                ", instanceCount=" + instanceCount +
                ", instanceSpreadDegrees=" + instanceSpreadDegrees +
                ", rotateModelWithOrbit=" + rotateModelWithOrbit +
                '}';
    }
}
