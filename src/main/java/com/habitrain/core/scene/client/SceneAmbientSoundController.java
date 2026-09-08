package com.habitrain.core.scene.client;

import com.habitrain.core.scene.model.SceneSoundSettings;
import com.habitrain.core.client.RepairModeClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 场景环境音效控制器（管理窗外列车行进呼啸声循环播放与淡入淡出）。
 */
public final class SceneAmbientSoundController {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneAmbientSoundController.class.getSimpleName());

    private static final SceneAmbientSoundController INSTANCE = new SceneAmbientSoundController();

    public static SceneAmbientSoundController getInstance() {
        return INSTANCE;
    }

    private MovingSceneLoopSound currentSound = null;

    static final class MovingSceneLoopSound extends AbstractTickableSoundInstance {
        private final float targetVolume;
        private final int fadeTicks;
        private int currentTicks = 0;
        private boolean fadingOut = false;

        MovingSceneLoopSound(SoundEvent soundEvent, float volume, float pitch, int fadeTicks) {
            super(soundEvent, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.targetVolume = volume;
            this.pitch = pitch;
            this.fadeTicks = Math.max(1, fadeTicks);
            this.volume = RepairModeClientState.isLocalRepairer() ? 0.0f : 0.01f;
            this.relative = true;
        }

        @Override
        public boolean canStartSilent() {
            // 维修时收到的新场景从零音量开始，退出维修后仍可正常淡入。
            return true;
        }

        @Override
        public void tick() {
            // 保留正式场景/预览各自的生命周期，维修结束后自然恢复；
            // 已经开始淡出的旧实例仍须走完清理，不能因维修静音而滞留。
            if (RepairModeClientState.isLocalRepairer() && !fadingOut) {
                currentTicks = 0;
                this.volume = 0.0f;
                return;
            }
            if (fadingOut) {
                currentTicks--;
                this.volume = (float) Math.max(0.0, targetVolume * (currentTicks / (double) fadeTicks));
                if (currentTicks <= 0) {
                    stop();
                }
            } else {
                if (currentTicks < fadeTicks) {
                    currentTicks++;
                    this.volume = (float) Math.min(targetVolume, targetVolume * (currentTicks / (double) fadeTicks));
                } else {
                    this.volume = targetVolume;
                }
            }
            if (RepairModeClientState.isLocalRepairer()) {
                this.volume = 0.0f;
            }
        }

        void fadeOut() {
            this.fadingOut = true;
        }
    }

    private SceneAmbientSoundController() {}

    public synchronized void playSound(SceneSoundSettings settings) {
        if (settings == null || !settings.isEnabled()
                || settings.getSoundId().isBlank() || settings.getVolume() <= 0) {
            stopSound();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() == null) return;

        stopSound();

        ResourceLocation loc = ResourceLocation.tryParse(settings.getSoundId());
        if (loc == null) return;

        SoundEvent soundEvent = SoundEvent.createVariableRangeEvent(loc);
        currentSound = new MovingSceneLoopSound(soundEvent, (float) settings.getVolume(), (float) settings.getPitch(), settings.getFadeTicks());
        mc.getSoundManager().play(currentSound);
        LOGGER.debug("开始播放场景环境循环音效: soundId={}", settings.getSoundId());
    }

    public synchronized void stopSound() {
        if (currentSound != null) {
            currentSound.fadeOut();
            currentSound = null;
        }
    }
}
