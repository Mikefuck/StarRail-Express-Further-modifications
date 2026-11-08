package com.habitrain.core.config;

import com.habitrain.core.api.scene.SceneMotionSettings;

import org.jetbrains.annotations.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ConfigRepository {
    public ConfigRepository copy() {
        ConfigRepository result = new ConfigRepository();
        result.copyFrom(this);
        return result;
    }

    /** Deep snapshot: the client editor never shares mutable config objects with server ticks. */
    public void copyFrom(ConfigRepository source) {
        if (source == this) return;
        taskConfigs.clear();
        source.taskConfigs.forEach((key, value) -> {
            if (key != null && value != null) taskConfigs.put(key, value.copy());
        });
        gameModeConfigs.clear();
        source.gameModeConfigs.forEach((key, value) -> {
            if (key != null && value != null) gameModeConfigs.put(key, value.copy());
        });
        minigameConfigs.clear();
        source.minigameConfigs.forEach((key, value) -> {
            if (key != null && value != null) minigameConfigs.put(key, value.copy());
        });
        setShaderWhitelistConfig(source.shaderWhitelistEnabled, new ArrayList<>(source.shaderWhitelist));
        dlcProbabilityTarget = source.dlcProbabilityTarget;
        sheriffCountDivisor = source.sheriffCountDivisor;
        minigameGlobalEnabled = source.minigameGlobalEnabled;
        knifeDurabilityEnabled = source.knifeDurabilityEnabled;
        lobbyVoiceGroupEnabled = source.lobbyVoiceGroupEnabled;
        blackoutGlobalCooldownSeconds = source.blackoutGlobalCooldownSeconds;
        blackoutEffectEnhancementEnabled = source.blackoutEffectEnhancementEnabled;
        modeMapVote = ModeMapVoteSettings.fromJson(source.getModeMapVote().toJson());
        environment = EnvironmentSettings.fromJson(source.getEnvironment().toJson());
        sceneMotion = SceneMotionSettings.fromJson(source.getSceneMotion().toJson());
    }
    private final Map<String, TaskConfigEntry> taskConfigs = new ConcurrentHashMap<>();
    private final Map<String, GameModeConfigScope> gameModeConfigs = new ConcurrentHashMap<>();
    private final Map<String, MinigameConfigEntry> minigameConfigs = new ConcurrentHashMap<>();
    private final List<String> shaderWhitelist = new CopyOnWriteArrayList<>();
    private volatile float dlcProbabilityTarget = 0.5f;
    private volatile int sheriffCountDivisor = 6;
    private volatile boolean minigameGlobalEnabled = true;
    private volatile boolean shaderWhitelistEnabled = false;
    private volatile boolean knifeDurabilityEnabled = false;
    private volatile boolean lobbyVoiceGroupEnabled = true;
    /** SRE 停电成功后施加给全体玩家的通用物品冷却；0 表示不传播通用冷却。 */
    private volatile int blackoutGlobalCooldownSeconds = BlackoutGlobalCooldownRules.DEFAULT_SECONDS;
    /** 停电黑暗时长增强：普通停电 20 秒黑暗+失明，忍者 10 秒（默认关闭，关闭时保持 SRE 原版 10 秒）。 */
    private volatile boolean blackoutEffectEnhancementEnabled = false;
    private volatile ModeMapVoteSettings modeMapVote = ModeMapVoteSettings.createDefault();
    private volatile EnvironmentSettings environment = EnvironmentSettings.createDefault();
    private volatile SceneMotionSettings sceneMotion = SceneMotionSettings.createDefault();
    @Nullable private volatile Runnable onSaveCallback = null;
    private volatile boolean suppressCallback = false;

    public TaskConfigEntry getTaskConfig(String fullId) {
        return fullId == null ? null : taskConfigs.get(fullId);
    }

    public void setTaskConfig(String fullId, TaskConfigEntry entry) {
        putTaskConfig(fullId, entry);
    }

    public void putTaskConfig(String fullId, TaskConfigEntry entry) {
        if (fullId == null) return;
        if (entry == null) taskConfigs.remove(fullId);
        else taskConfigs.put(fullId, entry);
    }

    public Map<String, TaskConfigEntry> getAllConfigs() {
        return Collections.unmodifiableMap(taskConfigs);
    }

    public void setAllConfigs(Map<String, TaskConfigEntry> entries) {
        if (entries != null) taskConfigs.putAll(entries);
    }

    Map<String, TaskConfigEntry> getMutableTaskConfigs() {
        return taskConfigs;
    }

    public GameModeConfigScope getGameModeConfig(String gameModeId) {
        if (gameModeId == null) return null;
        GameModeConfigScope existing = gameModeConfigs.get(gameModeId);
        return existing != null ? existing : new GameModeConfigScope(gameModeId);
    }

    public GameModeConfigScope getOrCreateGameModeConfig(String gameModeId) {
        if (gameModeId == null) return null;
        return gameModeConfigs.computeIfAbsent(gameModeId, GameModeConfigScope::new);
    }

    public void setGameModeConfig(String gameModeId, GameModeConfigScope scope) {
        if (gameModeId == null) return;
        if (scope == null) gameModeConfigs.remove(gameModeId);
        else gameModeConfigs.put(gameModeId, scope);
    }

    public Map<String, GameModeConfigScope> getAllGameModeConfigs() {
        return Collections.unmodifiableMap(gameModeConfigs);
    }

    Map<String, GameModeConfigScope> getMutableGameModeConfigs() {
        return gameModeConfigs;
    }

    public float getDlcProbabilityTarget() { return dlcProbabilityTarget; }

    public void setDlcProbabilityTarget(float target) {
        if (!Float.isFinite(target)) throw new IllegalArgumentException("DLC probability must be finite");
        this.dlcProbabilityTarget = Math.max(0.1f, Math.min(0.8f, target));
    }

    public int getSheriffCountDivisor() { return sheriffCountDivisor; }

    public void setSheriffCountDivisor(int divisor) {
        this.sheriffCountDivisor = Math.max(1, divisor);
    }

    public boolean isKnifeDurabilityEnabled() { return knifeDurabilityEnabled; }

    public void setKnifeDurabilityEnabled(boolean enabled) {
        this.knifeDurabilityEnabled = enabled;
    }

    /** 对局结束后 / 进服 / 大厅巡检时是否把玩家拉入 LobbyChat 大厅语音群组。 */
    public boolean isLobbyVoiceGroupEnabled() { return lobbyVoiceGroupEnabled; }

    public void setLobbyVoiceGroupEnabled(boolean enabled) {
        this.lobbyVoiceGroupEnabled = enabled;
    }

    public int getBlackoutGlobalCooldownSeconds() { return blackoutGlobalCooldownSeconds; }

    public void setBlackoutGlobalCooldownSeconds(int seconds) {
        this.blackoutGlobalCooldownSeconds = BlackoutGlobalCooldownRules.clampSeconds(seconds);
    }

    public boolean isBlackoutEffectEnhancementEnabled() { return blackoutEffectEnhancementEnabled; }

    public void setBlackoutEffectEnhancementEnabled(boolean enabled) {
        this.blackoutEffectEnhancementEnabled = enabled;
    }

    public boolean isShaderWhitelistEnabled() { return shaderWhitelistEnabled; }

    public void setShaderWhitelistEnabled(boolean enabled) {
        this.shaderWhitelistEnabled = enabled;
    }

    public List<String> getShaderWhitelist() {
        return List.copyOf(shaderWhitelist);
    }

    /** Package-private mutable view for ConfigStore / ConfigSync loaders. */
    List<String> getMutableShaderWhitelist() {
        return shaderWhitelist;
    }

    public void setShaderWhitelist(List<String> list) {
        shaderWhitelist.clear();
        if (list != null) {
            for (String s : list) {
                if (s != null) shaderWhitelist.add(s);
            }
        }
    }

    public void setShaderWhitelistConfig(boolean enabled, List<String> list) {
        this.shaderWhitelistEnabled = enabled;
        setShaderWhitelist(list);
    }

    public MinigameConfigEntry getMinigameConfig(String minigameId) {
        return minigameId == null ? null : minigameConfigs.get(minigameId);
    }

    public MinigameConfigEntry getOrCreateMinigameConfig(String minigameId) {
        if (minigameId == null) return MinigameConfigEntry.createDefault();
        return minigameConfigs.computeIfAbsent(minigameId, k -> MinigameConfigEntry.createDefault());
    }

    public void setMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        putMinigameConfig(minigameId, entry);
    }

    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        if (minigameId == null) return;
        if (entry == null) minigameConfigs.remove(minigameId);
        else minigameConfigs.put(minigameId, entry);
    }

    public Map<String, MinigameConfigEntry> getAllMinigameConfigs() {
        return Collections.unmodifiableMap(minigameConfigs);
    }

    public void setMinigames(Map<String, MinigameConfigEntry> entries) {
        minigameConfigs.clear();
        if (entries != null) {
            entries.forEach((k, v) -> {
                if (k != null && v != null) minigameConfigs.put(k, v.copy());
            });
        }
    }

    Map<String, MinigameConfigEntry> getMutableMinigameConfigs() {
        return minigameConfigs;
    }

    public boolean isMinigameGlobalEnabled() { return minigameGlobalEnabled; }

    public void setMinigameGlobalEnabled(boolean enabled) {
        this.minigameGlobalEnabled = enabled;
    }

    public boolean isMinigameEnabledForMap(String minigameId, String mapName) {
        if (!minigameGlobalEnabled) return false;
        MinigameConfigEntry entry = minigameConfigs.get(minigameId);
        if (entry == null) return true;
        if (!entry.enabled) return false;
        return entry.isAllowedOnMap(mapName);
    }

    public ModeMapVoteSettings getModeMapVote() { return modeMapVote; }

    public void setModeMapVote(ModeMapVoteSettings s) {
        this.modeMapVote = s != null ? s : ModeMapVoteSettings.createDefault();
    }

    public EnvironmentSettings getEnvironment() {
        return environment != null ? environment : EnvironmentSettings.createDefault();
    }

    public void setEnvironment(EnvironmentSettings s) {
        this.environment = s != null ? s : EnvironmentSettings.createDefault();
    }

    public SceneMotionSettings getSceneMotion() {
        return sceneMotion != null ? sceneMotion : SceneMotionSettings.createDefault();
    }

    public void setSceneMotion(SceneMotionSettings s) {
        this.sceneMotion = s != null ? s : SceneMotionSettings.createDefault();
    }

    @Nullable
    public Runnable getOnSaveCallback() { return onSaveCallback; }

    public void setOnSaveCallback(@Nullable Runnable callback) {
        this.onSaveCallback = callback;
    }

    public boolean isSuppressCallback() { return suppressCallback; }

    public void setSuppressCallback(boolean suppress) {
        this.suppressCallback = suppress;
    }
}
