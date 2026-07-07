package com.habitrain.core.config;

import com.habitrain.core.task.TaskPoolBuilder;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;

public class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ConfigManager");
    private static volatile ConfigManager INSTANCE;

    private final ConfigRepository repository;
    private final ConfigStore store;
    private final ConfigSync sync;
    private final MinigameEnforcement enforcement;

    public static void setOnSaveCallback(@Nullable Runnable callback) {
        getInstance().repository.setOnSaveCallback(callback);
    }

    private ConfigManager() {
        this.repository = new ConfigRepository();
        this.store = new ConfigStore();
        this.sync = new ConfigSync(store);
        this.enforcement = new MinigameEnforcement(store);
    }

    public static ConfigManager getInstance() {
        if (INSTANCE == null) {
            synchronized (ConfigManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ConfigManager();
                }
            }
        }
        return INSTANCE;
    }

    public void load() {
        store.load(repository);
    }

    public void save() {
        store.save(repository);
        if (!repository.isSuppressCallback()) {
            Runnable cb = repository.getOnSaveCallback();
            if (cb != null) {
                try { cb.run(); } catch (Exception e) {
                    LOGGER.error("保存回调执行失败", e);
                }
            }
        }
    }

    public TaskConfigEntry getTaskConfig(String fullId) {
        return repository.getTaskConfig(fullId);
    }

    public void setTaskConfig(String fullId, TaskConfigEntry entry) {
        repository.setTaskConfig(fullId, entry);
        store.save(repository);
        TaskPoolBuilder.invalidateAll();
    }

    public void putTaskConfig(String fullId, TaskConfigEntry entry) {
        repository.putTaskConfig(fullId, entry);
    }

    public Map<String, TaskConfigEntry> getAllConfigs() {
        return repository.getAllConfigs();
    }

    public void setAllConfigs(Map<String, TaskConfigEntry> entries) {
        repository.setAllConfigs(entries);
        store.save(repository);
    }

    public GameModeConfigScope getGameModeConfig(String gameModeId) {
        return repository.getGameModeConfig(gameModeId);
    }

    public Map<String, GameModeConfigScope> getAllGameModeConfigs() {
        return repository.getAllGameModeConfigs();
    }

    public long countDlcTasks() {
        return store.countDlcTasks();
    }

    public long countOriginalTasks() {
        return store.countOriginalTasks();
    }

    public float getDlcWeightBoost() {
        return store.getDlcWeightBoost(repository);
    }

    public float getDlcProbabilityTarget() {
        return repository.getDlcProbabilityTarget();
    }

    public void setDlcProbabilityTarget(float target) {
        repository.setDlcProbabilityTarget((float) Math.max(0.1, Math.min(0.8, target)));
        store.save(repository);
    }

    public boolean isShaderWhitelistEnabled() {
        return repository.isShaderWhitelistEnabled();
    }

    public void setShaderWhitelistEnabled(boolean enabled) {
        repository.setShaderWhitelistEnabled(enabled);
        store.save(repository);
    }

    public List<String> getShaderWhitelist() {
        return repository.getShaderWhitelist();
    }

    public void setShaderWhitelist(List<String> list) {
        repository.setShaderWhitelist(list);
        store.save(repository);
    }

    public void setShaderWhitelistConfig(boolean enabled, List<String> list) {
        repository.setShaderWhitelistConfig(enabled, list);
        store.save(repository);
    }

    public void applyShaderWhitelistSync(boolean enabled, List<String> list) {
        sync.applyShaderWhitelistSync(repository, enabled, list);
    }

    public String toJsonString() {
        return store.toJsonString(repository);
    }

    public void loadFromJsonString(String json) {
        sync.loadFromJsonString(repository, json);
    }

    public void applySyncData(Map<String, TaskConfigEntry> configs, float target) {
        sync.applySyncData(repository, configs, target);
    }

    public void applySyncFromJson(String json) {
        sync.applySyncFromJson(repository, json);
    }

    public File getConfigFile() {
        return store.getConfigFile();
    }

    public int getSheriffCountDivisor() {
        return repository.getSheriffCountDivisor();
    }

    public void setSheriffCountDivisor(int divisor) {
        repository.setSheriffCountDivisor(divisor);
        store.save(repository);
    }

    public MinigameConfigEntry getMinigameConfig(String minigameId) {
        return repository.getMinigameConfig(minigameId);
    }

    public MinigameConfigEntry getOrCreateMinigameConfig(String minigameId) {
        return repository.getOrCreateMinigameConfig(minigameId);
    }

    public void setMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository.setMinigameConfig(minigameId, entry);
        store.save(repository);
    }

    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository.putMinigameConfig(minigameId, entry);
    }

    public Map<String, MinigameConfigEntry> getAllMinigameConfigs() {
        return repository.getAllMinigameConfigs();
    }

    public boolean isMinigameGlobalEnabled() {
        return repository.isMinigameGlobalEnabled();
    }

    public void setMinigameGlobalEnabled(boolean enabled) {
        repository.setMinigameGlobalEnabled(enabled);
        store.save(repository);
    }

    public boolean isMinigameEnabledForMap(String minigameId, String mapName) {
        return repository.isMinigameEnabledForMap(minigameId, mapName);
    }

    public void applyMinigameEnforcement(@Nullable MinecraftServer server) {
        enforcement.apply(server, repository);
    }
}
