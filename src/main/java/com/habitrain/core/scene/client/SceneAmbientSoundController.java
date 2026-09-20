package com.habitrain.core.scene.client;

import com.habitrain.core.api.scene.model.SceneSoundSettings;
import com.habitrain.core.client.RepairModeClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 场景环境音效控制器（管理窗外列车行进呼啸声循环播放与淡入淡出）。
 *
 * <p>支持<b>多个并存</b>的循环音轨：地图级主场景占用保留键 {@link #PRIMARY_KEY}，
 * 每个 API 场景实例占用 {@code instance:&lt;实例ID&gt;}。这样"主场景 + 若干实例"可以各自
 * 拥有独立的车外音，互不打断淡入淡出。</p>
 */
public final class SceneAmbientSoundController {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneAmbientSoundController.class.getSimpleName());

    /** 地图级主场景（含设置页预览）保留的音轨键。 */
    public static final String PRIMARY_KEY = "__primary__";
    /** API 场景实例音轨键前缀。 */
    public static final String INSTANCE_KEY_PREFIX = "instance:";

    private static final SceneAmbientSoundController INSTANCE = new SceneAmbientSoundController();

    public static SceneAmbientSoundController getInstance() {
        return INSTANCE;
    }

    private final Map<String, MovingSceneLoopSound> sounds = new LinkedHashMap<>();

    /** 某个音轨当前的设置，用于判断"要不要重新起播"。 */
    private final Map<String, SceneSoundSettings> activeSettings = new LinkedHashMap<>();

    /** API 实例音轨键。 */
    public static String instanceKey(String instanceId) {
        return INSTANCE_KEY_PREFIX + (instanceId != null ? instanceId : "");
    }

    static final class MovingSceneLoopSound extends AbstractTickableSoundInstance {
        private final String key;
        private final float targetVolume;
        private final int fadeTicks;
        private int currentTicks = 0;
        private boolean fadingOut = false;

        MovingSceneLoopSound(String key, SoundEvent soundEvent, float volume, float pitch, int fadeTicks) {
            super(soundEvent, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.key = key;
            this.looping = true;
            this.delay = 0;
            this.targetVolume = volume;
            this.pitch = pitch;
            this.fadeTicks = Math.max(1, fadeTicks);
            this.volume = RepairModeClientState.isLocalRepairer() ? 0.0f : 0.01f;
            this.relative = true;
        }

        String key() {
            return key;
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

    /** 播放/替换地图级主场景音轨（等价于 {@link #PRIMARY_KEY}）。 */
    public synchronized void playSound(SceneSoundSettings settings) {
        playSound(PRIMARY_KEY, settings);
    }

    /**
     * 播放/替换指定键的音轨。设置与当前正在播放的完全一致时不会重播（避免每 tick 重置淡入）。
     */
    public synchronized void playSound(String key, SceneSoundSettings settings) {
        String trackKey = key != null ? key : PRIMARY_KEY;
        if (settings == null || !settings.isEnabled()
                || settings.getSoundId().isBlank() || settings.getVolume() <= 0) {
            stopSound(trackKey);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() == null) return;

        SceneSoundSettings current = activeSettings.get(trackKey);
        MovingSceneLoopSound playing = sounds.get(trackKey);
        if (playing != null && settings.equals(current)) {
            return;
        }

        stopSound(trackKey);

        ResourceLocation loc = ResourceLocation.tryParse(settings.getSoundId());
        if (loc == null) return;

        SoundEvent soundEvent = SoundEvent.createVariableRangeEvent(loc);
        MovingSceneLoopSound sound = new MovingSceneLoopSound(trackKey, soundEvent,
                (float) settings.getVolume(), (float) settings.getPitch(), settings.getFadeTicks());
        sounds.put(trackKey, sound);
        activeSettings.put(trackKey, settings);
        mc.getSoundManager().play(sound);
        LOGGER.debug("开始播放场景环境循环音效: track={}, soundId={}", trackKey, settings.getSoundId());
    }

    /** 停止地图级主场景音轨（不影响 API 实例音轨）。 */
    public synchronized void stopSound() {
        stopSound(PRIMARY_KEY);
    }

    /** 停止指定音轨。 */
    public synchronized void stopSound(String key) {
        String trackKey = key != null ? key : PRIMARY_KEY;
        activeSettings.remove(trackKey);
        MovingSceneLoopSound sound = sounds.remove(trackKey);
        if (sound != null) {
            sound.fadeOut();
        }
    }

    /** 停止全部音轨（换会话/断开连接时必须调用）。 */
    public synchronized void stopAllSounds() {
        for (MovingSceneLoopSound sound : sounds.values()) {
            sound.fadeOut();
        }
        sounds.clear();
        activeSettings.clear();
    }

    /**
     * 只保留给定键集合的 API 实例音轨：集合外的实例音轨淡出，集合内缺失的重新起播。
     *
     * <p><b>不触碰</b>地图级场景的主音轨（{@link #PRIMARY_KEY}）与其它非实例音轨——
     * 它们的生命周期由地图级场景/预览各自管理，由本方法统一"对齐"会把预览与正式状态互相打断。</p>
     *
     * @param desired 实例音轨键 → 设置
     */
    public synchronized void retainOnly(Map<String, SceneSoundSettings> desired) {
        Map<String, SceneSoundSettings> wanted = desired != null ? desired : Map.of();
        List<String> stale = sounds.keySet().stream()
                .filter(key -> key.startsWith(INSTANCE_KEY_PREFIX) && !wanted.containsKey(key))
                .toList();
        for (String key : stale) {
            stopSound(key);
        }
        for (Map.Entry<String, SceneSoundSettings> entry : wanted.entrySet()) {
            playSound(entry.getKey(), entry.getValue());
        }
    }

    /** 当前正在播放的音轨键集合（诊断用）。 */
    public synchronized Set<String> activeTracks() {
        return Set.copyOf(sounds.keySet());
    }

    /** 某音轨是否正在播放。 */
    public synchronized boolean isPlaying(String key) {
        return sounds.containsKey(Objects.requireNonNullElse(key, PRIMARY_KEY));
    }
}
