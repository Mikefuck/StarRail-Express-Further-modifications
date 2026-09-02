package com.habitrain.core.scene.client;

import com.habitrain.core.scene.model.SceneShakeSettings;

/**
 * 场景镜头震动控制器（通过多频正弦谐波叠加生成程序化车厢微震，并在第一人称/第三人称视口微调相机）。
 */
public final class SceneShakeController {
    private static final SceneShakeController INSTANCE = new SceneShakeController();

    public static SceneShakeController getInstance() {
        return INSTANCE;
    }

    private SceneShakeSettings currentSettings = SceneShakeSettings.createDisabled();
    private boolean active = false;
    private long startTimeMs = 0L;

    private SceneShakeController() {}

    public synchronized void updateSettings(SceneShakeSettings settings, boolean active) {
        this.currentSettings = settings != null ? settings : SceneShakeSettings.createDisabled();
        this.active = active && this.currentSettings.isEnabled();
        if (this.active && this.startTimeMs == 0L) {
            this.startTimeMs = System.currentTimeMillis();
        } else if (!this.active) {
            this.startTimeMs = 0L;
        }
    }

    public boolean isActive() {
        return active && currentSettings.isEnabled();
    }

    /**
     * 计算相机旋转偏移量 (pitch, yaw, roll)（单位：度）。
     */
    public float[] getRotationOffset(float partialTick) {
        if (!isActive()) return new float[]{0f, 0f, 0f};

        double t = (System.currentTimeMillis() - startTimeMs) / 1000.0;
        double freq = currentSettings.getFrequencyHz();
        double amp = currentSettings.getRotationAmplitudeDegrees();

        // 使用不同频率的多频正弦谐波叠加避免机械式重复
        float pitch = (float) (amp * (Math.sin(t * freq * 2.0 * Math.PI) * 0.6 + Math.sin(t * freq * 1.3 * Math.PI) * 0.4));
        float yaw = (float) (amp * (Math.cos(t * freq * 1.7 * Math.PI) * 0.5 + Math.sin(t * freq * 0.8 * Math.PI) * 0.5));
        float roll = (float) (amp * 0.5 * Math.sin(t * freq * 1.1 * Math.PI));

        return new float[]{pitch, yaw, roll};
    }

    /**
     * 计算相机位置偏移量 (x, y, z)（单位：格）。
     */
    public float[] getPositionOffset(float partialTick) {
        if (!isActive()) return new float[]{0f, 0f, 0f};

        double t = (System.currentTimeMillis() - startTimeMs) / 1000.0;
        double freq = currentSettings.getFrequencyHz();
        double amp = currentSettings.getTranslationAmplitudeBlocks();

        float y = (float) (amp * (Math.sin(t * freq * 2.0 * Math.PI) * 0.7 + Math.sin(t * freq * 3.1 * Math.PI) * 0.3));
        float x = (float) (amp * 0.4 * Math.cos(t * freq * 1.5 * Math.PI));
        float z = (float) (amp * 0.4 * Math.sin(t * freq * 1.2 * Math.PI));

        return new float[]{x, y, z};
    }
}
