package com.habitrain.core.scene.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Objects;

/**
 * 单张地图的移动场景配置 Profile。
 */
public final class SceneProfile {
    public static final String DEFAULT_DIMENSION = "minecraft:overworld";
    public static final double DEFAULT_SPEED = 18.0;

    private boolean enabled;
    private String dimension;
    private SceneBounds sourceBounds;
    private double[] displayOrigin; // [x, y, z]
    private double[] pivotLocal;    // [x, y, z]
    private double[] direction;     // [x, y, z] normalized
    private double speedBlocksPerSecond;
    private SceneRotation rotationDegrees;
    private double phaseOffsetBlocks;
    private SceneLoopSettings loop;
    private SceneRenderSettings render;
    private SceneSoundSettings outsideSound;
    private SceneShakeSettings shake;

    public SceneProfile() {
        this.enabled = false;
        this.dimension = DEFAULT_DIMENSION;
        this.sourceBounds = SceneBounds.EMPTY;
        this.displayOrigin = new double[]{0.0, 0.0, 0.0};
        this.pivotLocal = new double[]{0.0, 0.0, 0.0};
        this.direction = new double[]{-1.0, 0.0, 0.0};
        this.speedBlocksPerSecond = DEFAULT_SPEED;
        this.rotationDegrees = SceneRotation.ZERO;
        this.phaseOffsetBlocks = 0.0;
        this.loop = SceneLoopSettings.createDefault();
        this.render = SceneRenderSettings.createDefault();
        this.outsideSound = SceneSoundSettings.createDefault();
        this.shake = SceneShakeSettings.createDefault();
    }

    public static SceneProfile createDefault() {
        return new SceneProfile();
    }

    public SceneProfile copy() {
        SceneProfile c = new SceneProfile();
        c.enabled = this.enabled;
        c.dimension = this.dimension;
        c.sourceBounds = this.sourceBounds;
        c.displayOrigin = Arrays.copyOf(this.displayOrigin, 3);
        c.pivotLocal = Arrays.copyOf(this.pivotLocal, 3);
        c.direction = Arrays.copyOf(this.direction, 3);
        c.speedBlocksPerSecond = this.speedBlocksPerSecond;
        c.rotationDegrees = this.rotationDegrees;
        c.phaseOffsetBlocks = this.phaseOffsetBlocks;
        c.loop = this.loop;
        c.render = this.render;
        c.outsideSound = this.outsideSound;
        c.shake = this.shake;
        return c;
    }

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDimension() { return dimension != null ? dimension : DEFAULT_DIMENSION; }
    public void setDimension(String dimension) { this.dimension = (dimension == null || dimension.isBlank()) ? DEFAULT_DIMENSION : dimension.trim(); }

    public SceneBounds getSourceBounds() { return sourceBounds != null ? sourceBounds : SceneBounds.EMPTY; }
    public void setSourceBounds(SceneBounds sourceBounds) { this.sourceBounds = sourceBounds != null ? sourceBounds : SceneBounds.EMPTY; }

    public double[] getDisplayOrigin() { return displayOrigin; }
    public void setDisplayOrigin(double x, double y, double z) {
        this.displayOrigin = new double[]{
                validateFinite(x, 0.0),
                validateFinite(y, 0.0),
                validateFinite(z, 0.0)
        };
    }

    public double[] getPivotLocal() { return pivotLocal; }
    public void setPivotLocal(double x, double y, double z) {
        this.pivotLocal = new double[]{
                validateFinite(x, 0.0),
                validateFinite(y, 0.0),
                validateFinite(z, 0.0)
        };
    }

    public double[] getDirection() { return direction; }
    public void setDirection(double x, double y, double z) {
        double dx = validateFinite(x, -1.0);
        double dy = validateFinite(y, 0.0);
        double dz = validateFinite(z, 0.0);
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) {
            this.direction = new double[]{-1.0, 0.0, 0.0};
        } else {
            this.direction = new double[]{dx / len, dy / len, dz / len};
        }
    }

    public double getSpeedBlocksPerSecond() { return speedBlocksPerSecond; }
    public void setSpeedBlocksPerSecond(double speed) {
        double s = validateFinite(speed, DEFAULT_SPEED);
        this.speedBlocksPerSecond = Math.max(0.0, Math.min(64.0, s));
    }

    public SceneRotation getRotationDegrees() { return rotationDegrees != null ? rotationDegrees : SceneRotation.ZERO; }
    public void setRotationDegrees(SceneRotation rotationDegrees) { this.rotationDegrees = rotationDegrees != null ? rotationDegrees : SceneRotation.ZERO; }

    public double getPhaseOffsetBlocks() { return phaseOffsetBlocks; }
    public void setPhaseOffsetBlocks(double offset) { this.phaseOffsetBlocks = validateFinite(offset, 0.0); }

    public SceneLoopSettings getLoop() { return loop != null ? loop : SceneLoopSettings.createDefault(); }
    public void setLoop(SceneLoopSettings loop) { this.loop = loop != null ? loop : SceneLoopSettings.createDefault(); }

    public SceneRenderSettings getRender() { return render != null ? render : SceneRenderSettings.createDefault(); }
    public void setRender(SceneRenderSettings render) { this.render = render != null ? render : SceneRenderSettings.createDefault(); }

    public SceneSoundSettings getOutsideSound() { return outsideSound != null ? outsideSound : SceneSoundSettings.createDefault(); }
    public void setOutsideSound(SceneSoundSettings sound) { this.outsideSound = sound != null ? sound : SceneSoundSettings.createDefault(); }

    public SceneShakeSettings getShake() { return shake != null ? shake : SceneShakeSettings.createDefault(); }
    public void setShake(SceneShakeSettings shake) { this.shake = shake != null ? shake : SceneShakeSettings.createDefault(); }

    private static double validateFinite(double val, double fallback) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return fallback;
        return val;
    }

    // --- JSON Codec ---

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("dimension", getDimension());
        json.add("sourceBounds", getSourceBounds().toJson());

        JsonArray dispArr = new JsonArray();
        dispArr.add(displayOrigin[0]);
        dispArr.add(displayOrigin[1]);
        dispArr.add(displayOrigin[2]);
        json.add("displayOrigin", dispArr);

        JsonArray pivArr = new JsonArray();
        pivArr.add(pivotLocal[0]);
        pivArr.add(pivotLocal[1]);
        pivArr.add(pivotLocal[2]);
        json.add("pivotLocal", pivArr);

        JsonArray dirArr = new JsonArray();
        dirArr.add(direction[0]);
        dirArr.add(direction[1]);
        dirArr.add(direction[2]);
        json.add("direction", dirArr);

        json.addProperty("speedBlocksPerSecond", speedBlocksPerSecond);
        json.add("rotationDegrees", getRotationDegrees().toJson());
        json.addProperty("phaseOffsetBlocks", phaseOffsetBlocks);
        json.add("loop", getLoop().toJson());
        json.add("render", getRender().toJson());
        json.add("outsideSound", getOutsideSound().toJson());
        json.add("shake", getShake().toJson());
        return json;
    }

    public static SceneProfile fromJson(JsonObject json) {
        SceneProfile p = new SceneProfile();
        if (json == null) return p;

        if (json.has("enabled")) p.enabled = json.get("enabled").getAsBoolean();
        if (json.has("dimension")) p.setDimension(json.get("dimension").getAsString());
        if (json.has("sourceBounds") && json.get("sourceBounds").isJsonObject()) {
            p.sourceBounds = SceneBounds.fromJson(json.getAsJsonObject("sourceBounds"));
        }

        if (json.has("displayOrigin") && json.get("displayOrigin").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("displayOrigin");
            if (arr.size() >= 3) {
                p.setDisplayOrigin(arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble());
            }
        }
        if (json.has("pivotLocal") && json.get("pivotLocal").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("pivotLocal");
            if (arr.size() >= 3) {
                p.setPivotLocal(arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble());
            }
        }
        if (json.has("direction") && json.get("direction").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("direction");
            if (arr.size() >= 3) {
                p.setDirection(arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble());
            }
        }

        if (json.has("speedBlocksPerSecond")) {
            p.setSpeedBlocksPerSecond(json.get("speedBlocksPerSecond").getAsDouble());
        }
        if (json.has("rotationDegrees") && json.get("rotationDegrees").isJsonObject()) {
            p.rotationDegrees = SceneRotation.fromJson(json.getAsJsonObject("rotationDegrees"));
        }
        if (json.has("phaseOffsetBlocks")) {
            p.setPhaseOffsetBlocks(json.get("phaseOffsetBlocks").getAsDouble());
        }
        if (json.has("loop") && json.get("loop").isJsonObject()) {
            p.loop = SceneLoopSettings.fromJson(json.getAsJsonObject("loop"));
        }
        if (json.has("render") && json.get("render").isJsonObject()) {
            p.render = SceneRenderSettings.fromJson(json.getAsJsonObject("render"));
        }
        if (json.has("outsideSound") && json.get("outsideSound").isJsonObject()) {
            p.outsideSound = SceneSoundSettings.fromJson(json.getAsJsonObject("outsideSound"));
        }
        if (json.has("shake") && json.get("shake").isJsonObject()) {
            p.shake = SceneShakeSettings.fromJson(json.getAsJsonObject("shake"));
        }
        return p;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneProfile that)) return false;
        return enabled == that.enabled &&
                Double.compare(speedBlocksPerSecond, that.speedBlocksPerSecond) == 0 &&
                Double.compare(phaseOffsetBlocks, that.phaseOffsetBlocks) == 0 &&
                Objects.equals(dimension, that.dimension) &&
                Objects.equals(sourceBounds, that.sourceBounds) &&
                Arrays.equals(displayOrigin, that.displayOrigin) &&
                Arrays.equals(pivotLocal, that.pivotLocal) &&
                Arrays.equals(direction, that.direction) &&
                Objects.equals(rotationDegrees, that.rotationDegrees) &&
                Objects.equals(loop, that.loop) &&
                Objects.equals(render, that.render) &&
                Objects.equals(outsideSound, that.outsideSound) &&
                Objects.equals(shake, that.shake);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(enabled, dimension, sourceBounds, speedBlocksPerSecond, rotationDegrees, phaseOffsetBlocks, loop, render, outsideSound, shake);
        result = 31 * result + Arrays.hashCode(displayOrigin);
        result = 31 * result + Arrays.hashCode(pivotLocal);
        result = 31 * result + Arrays.hashCode(direction);
        return result;
    }
}
