package com.habitrain.core.config;

import com.habitrain.core.task.TaskPoolBuilder;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;

public class ConfigManager implements ConfigQueryService {
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
        if (store.commit(repository)) {
            if (!repository.isSuppressCallback()) {
                Runnable cb = repository.getOnSaveCallback();
                if (cb != null) {
                    try { cb.run(); } catch (Exception e) {
                        LOGGER.error("保存回调执行失败", e);
                    }
                }
            }
        }
    }

    @Override
    public TaskConfigEntry getTaskConfig(String fullId) {
        return repository.getTaskConfig(fullId);
    }

    @Override
    public boolean isTaskEnabled(String fullId, String mapName) {
        TaskConfigEntry entry = repository.getTaskConfig(fullId);
        if (entry == null) return true;
        if (!entry.enabled) return false;
        return isMapAllowed(fullId, mapName);
    }

    @Override
    public boolean isMapAllowed(String fullId, String mapName) {
        TaskConfigEntry entry = repository.getTaskConfig(fullId);
        if (entry == null) return true;
        if (entry.mapFilterMode == 0) return true;
        boolean listEmpty = entry.enabledMaps == null || entry.enabledMaps.isEmpty();
        boolean contained = !listEmpty && entry.enabledMaps.contains(mapName);
        if (entry.mapFilterMode == 1) return listEmpty || contained;
        return listEmpty || !contained;
    }

    public void setTaskConfig(String fullId, TaskConfigEntry entry) {
        repository.setTaskConfig(fullId, entry);
        store.markDirty();
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
        store.markDirty();
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

    @Override
    public float getDlcWeightBoost() {
        return store.getDlcWeightBoost(repository);
    }

    public float getDlcProbabilityTarget() {
        return repository.getDlcProbabilityTarget();
    }

    public void setDlcProbabilityTarget(float target) {
        repository.setDlcProbabilityTarget((float) Math.max(0.1, Math.min(0.8, target)));
        store.markDirty();
    }

    public boolean isShaderWhitelistEnabled() {
        return repository.isShaderWhitelistEnabled();
    }

    public void setShaderWhitelistEnabled(boolean enabled) {
        repository.setShaderWhitelistEnabled(enabled);
        store.markDirty();
    }

    public List<String> getShaderWhitelist() {
        return repository.getShaderWhitelist();
    }

    public void setShaderWhitelist(List<String> list) {
        repository.setShaderWhitelist(list);
        store.markDirty();
    }

    public void setShaderWhitelistConfig(boolean enabled, List<String> list) {
        repository.setShaderWhitelistConfig(enabled, list);
        store.markDirty();
    }

    public void applyShaderWhitelistSync(boolean enabled, List<String> list) {
        sync.applyShaderWhitelistSync(repository, enabled, list);
    }

    public String toJsonString() {
        return store.toJsonString(repository);
    }

    public void loadFromJsonString(String json) {
        sync.loadFromJsonString(repository, json);
        store.markDirty();
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
        store.markDirty();
    }

    @Override
    public MinigameConfigEntry getMinigameConfig(String minigameId) {
        return repository.getMinigameConfig(minigameId);
    }

    public MinigameConfigEntry getOrCreateMinigameConfig(String minigameId) {
        return repository.getOrCreateMinigameConfig(minigameId);
    }

    public void setMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository.setMinigameConfig(minigameId, entry);
        store.markDirty();
    }

    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository.putMinigameConfig(minigameId, entry);
    }

    public Map<String, MinigameConfigEntry> getAllMinigameConfigs() {
        return repository.getAllMinigameConfigs();
    }

    @Override
    public boolean isMinigameGlobalEnabled() {
        return repository.isMinigameGlobalEnabled();
    }

    public void setMinigameGlobalEnabled(boolean enabled) {
        repository.setMinigameGlobalEnabled(enabled);
        store.markDirty();
    }

    @Override
    public boolean isMinigameEnabledForMap(String minigameId, String mapName) {
        return repository.isMinigameEnabledForMap(minigameId, mapName);
    }

    public void applyMinigameEnforcement(@Nullable MinecraftServer server) {
        enforcement.apply(server, repository);
    }
}
