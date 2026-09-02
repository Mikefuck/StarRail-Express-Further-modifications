package com.habitrain.core.scene.model;

import java.util.Arrays;

/**
 * 场景配置编辑草稿（隔离页面临时输入与 ConfigManager 权威配置）。
 */
public final class SceneProfileDraft {
    private boolean enabled;
    private String dimension;
    private SceneBounds sourceBounds;
    private double[] displayOrigin;
    private double[] pivotLocal;
    private double[] direction;
    private double speedBlocksPerSecond;
    private SceneRotation rotationDegrees;
    private double phaseOffsetBlocks;
    private SceneLoopSettings loop;
    private SceneRenderSettings render;
    private SceneSoundSettings outsideSound;
    private SceneShakeSettings shake;

    public SceneProfileDraft() {
        this(new SceneProfile());
    }

    public SceneProfileDraft(SceneProfile profile) {
        loadFrom(profile);
    }

    public void loadFrom(SceneProfile p) {
        if (p == null) p = new SceneProfile();
        this.enabled = p.isEnabled();
        this.dimension = p.getDimension();
        this.sourceBounds = p.getSourceBounds();
        this.displayOrigin = Arrays.copyOf(p.getDisplayOrigin(), 3);
        this.pivotLocal = Arrays.copyOf(p.getPivotLocal(), 3);
        this.direction = Arrays.copyOf(p.getDirection(), 3);
        this.speedBlocksPerSecond = p.getSpeedBlocksPerSecond();
        this.rotationDegrees = p.getRotationDegrees();
        this.phaseOffsetBlocks = p.getPhaseOffsetBlocks();
        this.loop = p.getLoop();
        this.render = p.getRender();
        this.outsideSound = p.getOutsideSound();
        this.shake = p.getShake();
    }

    public SceneProfile toProfile() {
        SceneProfile p = new SceneProfile();
        p.setEnabled(enabled);
        p.setDimension(dimension);
        p.setSourceBounds(sourceBounds);
        p.setDisplayOrigin(displayOrigin[0], displayOrigin[1], displayOrigin[2]);
        p.setPivotLocal(pivotLocal[0], pivotLocal[1], pivotLocal[2]);
        p.setDirection(direction[0], direction[1], direction[2]);
        p.setSpeedBlocksPerSecond(speedBlocksPerSecond);
        p.setRotationDegrees(rotationDegrees);
        p.setPhaseOffsetBlocks(phaseOffsetBlocks);
        p.setLoop(loop);
        p.setRender(render);
        p.setOutsideSound(outsideSound);
        p.setShake(shake);
        return p;
    }

    // Getters & Setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    public SceneBounds getSourceBounds() { return sourceBounds; }
    public void setSourceBounds(SceneBounds sourceBounds) { this.sourceBounds = sourceBounds; }

    public double[] getDisplayOrigin() { return displayOrigin; }
    public void setDisplayOrigin(double x, double y, double z) {
        this.displayOrigin[0] = x;
        this.displayOrigin[1] = y;
        this.displayOrigin[2] = z;
    }

    public double[] getPivotLocal() { return pivotLocal; }
    public void setPivotLocal(double x, double y, double z) {
        this.pivotLocal[0] = x;
        this.pivotLocal[1] = y;
        this.pivotLocal[2] = z;
    }

    public double[] getDirection() { return direction; }
    public void setDirection(double x, double y, double z) {
        this.direction[0] = x;
        this.direction[1] = y;
        this.direction[2] = z;
    }

    public double getSpeedBlocksPerSecond() { return speedBlocksPerSecond; }
    public void setSpeedBlocksPerSecond(double speed) { this.speedBlocksPerSecond = speed; }

    public SceneRotation getRotationDegrees() { return rotationDegrees; }
    public void setRotationDegrees(SceneRotation rotationDegrees) { this.rotationDegrees = rotationDegrees; }

    public double getPhaseOffsetBlocks() { return phaseOffsetBlocks; }
    public void setPhaseOffsetBlocks(double phaseOffsetBlocks) { this.phaseOffsetBlocks = phaseOffsetBlocks; }

    public SceneLoopSettings getLoop() { return loop; }
    public void setLoop(SceneLoopSettings loop) { this.loop = loop; }

    public SceneRenderSettings getRender() { return render; }
    public void setRender(SceneRenderSettings render) { this.render = render; }

    public SceneSoundSettings getOutsideSound() { return outsideSound; }
    public void setOutsideSound(SceneSoundSettings outsideSound) { this.outsideSound = outsideSound; }

    public SceneShakeSettings getShake() { return shake; }
    public void setShake(SceneShakeSettings shake) { this.shake = shake; }
}
