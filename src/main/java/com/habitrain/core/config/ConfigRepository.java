package com.habitrain.core.config;

import org.jetbrains.annotations.Nullable;
import java.util.*;

public class ConfigRepository {
    private final Map<String, TaskConfigEntry> taskConfigs = new HashMap<>();
    private final Map<String, GameModeConfigScope> gameModeConfigs = new HashMap<>();
    private final Map<String, MinigameConfigEntry> minigameConfigs = new HashMap<>();
    private final List<String> shaderWhitelist = new ArrayList<>();
    private float dlcProbabilityTarget = 0.5f;
    private int sheriffCountDivisor = 6;
    private boolean minigameGlobalEnabled = true;
    private boolean shaderWhitelistEnabled = false;
    private int tempPowerPrice = 100;
    private boolean knifeDurabilityEnabled = false;
    private boolean lobbyVoiceGroupEnabled = true;
    /** 停电黑暗时长增强：普通停电 20 秒黑暗+失明，忍者 10 秒（默认关闭，关闭时保持 SRE 原版 10 秒）。 */
    private boolean blackoutEffectEnhancementEnabled = false;
    private ModeMapVoteSettings modeMapVote = ModeMapVoteSettings.createDefault();
    private EnvironmentSettings environment = EnvironmentSettings.createDefault();
    private RoleOverrideConfigSection roleOverrides = RoleOverrideConfigSection.createDefault();
    @Nullable private Runnable onSaveCallback = null;
    private boolean suppressCallback = false;

    public TaskConfigEntry getTaskConfig(String fullId) {
        return taskConfigs.get(fullId);
    }

    public void setTaskConfig(String fullId, TaskConfigEntry entry) {
        putTaskConfig(fullId, entry);
    }

    public void putTaskConfig(String fullId, TaskConfigEntry entry) {
        taskConfigs.put(fullId, entry);
    }

    public Map<String, TaskConfigEntry> getAllConfigs() {
        return Collections.unmodifiableMap(taskConfigs);
    }

    public void setAllConfigs(Map<String, TaskConfigEntry> entries) {
        taskConfigs.putAll(entries);
    }

    Map<String, TaskConfigEntry> getMutableTaskConfigs() {
        return taskConfigs;
    }

    public GameModeConfigScope getGameModeConfig(String gameModeId) {
        return gameModeConfigs.computeIfAbsent(gameModeId, GameModeConfigScope::new);
    }

    public Map<String, GameModeConfigScope> getAllGameModeConfigs() {
        return gameModeConfigs;
    }

    Map<String, GameModeConfigScope> getMutableGameModeConfigs() {
        return gameModeConfigs;
    }

    public float getDlcProbabilityTarget() { return dlcProbabilityTarget; }

    public void setDlcProbabilityTarget(float target) {
        this.dlcProbabilityTarget = target;
    }

    public int getSheriffCountDivisor() { return sheriffCountDivisor; }

    public void setSheriffCountDivisor(int divisor) {
        this.sheriffCountDivisor = Math.max(1, divisor);
    }

    public int getTempPowerPrice() { return tempPowerPrice; }

    public void setTempPowerPrice(int price) {
        this.tempPowerPrice = Math.max(0, price);
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

    public boolean isBlackoutEffectEnhancementEnabled() { return blackoutEffectEnhancementEnabled; }

    public void setBlackoutEffectEnhancementEnabled(boolean enabled) {
        this.blackoutEffectEnhancementEnabled = enabled;
    }

    public boolean isShaderWhitelistEnabled() { return shaderWhitelistEnabled; }

    public void setShaderWhitelistEnabled(boolean enabled) {
        this.shaderWhitelistEnabled = enabled;
    }

    public List<String> getShaderWhitelist() { return shaderWhitelist; }

    public void setShaderWhitelist(List<String> list) {
        shaderWhitelist.clear();
        shaderWhitelist.addAll(list);
    }

    public void setShaderWhitelistConfig(boolean enabled, List<String> list) {
        this.shaderWhitelistEnabled = enabled;
        shaderWhitelist.clear();
        shaderWhitelist.addAll(list);
    }

    public MinigameConfigEntry getMinigameConfig(String minigameId) {
        return minigameConfigs.get(minigameId);
    }

    public MinigameConfigEntry getOrCreateMinigameConfig(String minigameId) {
        return minigameConfigs.computeIfAbsent(minigameId, k -> MinigameConfigEntry.createDefault());
    }

    public void setMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        minigameConfigs.put(minigameId, entry);
    }

    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        minigameConfigs.put(minigameId, entry);
    }

    public Map<String, MinigameConfigEntry> getAllMinigameConfigs() {
        return minigameConfigs;
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

    public RoleOverrideConfigSection getRoleOverrides() { return roleOverrides; }

    public void setRoleOverrides(RoleOverrideConfigSection s) {
        this.roleOverrides = s != null ? s : RoleOverrideConfigSection.createDefault();
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
