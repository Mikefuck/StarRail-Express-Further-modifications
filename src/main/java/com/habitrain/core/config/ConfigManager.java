package com.habitrain.core.config;


import com.habitrain.core.game.sre.mode.KnifeDurabilityToggleService;
import com.habitrain.core.game.sre.mode.SREGameModeBase;
import com.habitrain.core.game.sre.config.SREIntegration;
import com.habitrain.core.api.spi.SceneConfigBridge;

import com.habitrain.core.api.scene.SceneMotionSettings;

import com.habitrain.core.task.TaskPoolBuilder;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;

public class ConfigManager implements ConfigQueryService, SceneConfigBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("ConfigManager");
    private static volatile ConfigManager INSTANCE;

    private volatile ConfigRepository repository;
    private volatile ConfigRepository localSnapshot = new ConfigRepository();
    private final ConfigStore store;
    private final ConfigSync sync;
    private final MinigameEnforcement enforcement;
    private volatile MinecraftServer currentServer;
    private final ThreadLocal<ClientConfigSession> clientSession = new ThreadLocal<>();
    private static volatile ClientSaveHandler clientSaveHandler;
    private long revision;
    private String revisionJson;

    @FunctionalInterface
    public interface ClientSaveHandler { ConfigSaveResult submit(String baseJson, String draftJson, long baseRevision); }
    public static void setClientSaveHandler(ClientSaveHandler handler) { clientSaveHandler = handler; }
    private ConfigRepository repository() { ClientConfigSession session = clientSession.get(); return session == null ? repository : session.draft; }
    private void markDirty() { ClientConfigSession session = clientSession.get(); if (session == null) store.markDirty(); else session.dirty = true; }
    public void installClientSession() { if (clientSession.get() == null) clientSession.set(new ClientConfigSession(localSnapshot, store)); }
    public void markClientConnected() { clientSession.set(new ClientConfigSession(localSnapshot, store)); clientSession.get().remote = true; clientSession.get().baseRevision = -1; }
    public void clearClientSession() { clientSession.set(new ClientConfigSession(localSnapshot, store)); }
    public void beginClientEditing() {
        installClientSession(); ClientConfigSession session = clientSession.get();
        if (!session.editing && !session.dirty) {
            session.draft.copyFrom(session.mirror); session.baseJson = store.toJsonString(session.draft);
            session.baseRevision = session.mirrorRevision;
        }
        session.editing = true;
    }
    public void endClientEditing() { ClientConfigSession session = clientSession.get(); if (session != null) session.editing = false; }
    public boolean hasClientConfigConflict() { ClientConfigSession session = clientSession.get(); return session != null && session.conflicted; }
    public void markClientConfigConflict() { ClientConfigSession session = clientSession.get(); if (session != null) session.conflicted = true; }
    public void reloadClientDraft() {
        ClientConfigSession session = clientSession.get(); if (session == null) return;
        session.draft.copyFrom(session.mirror); session.baseJson = store.toJsonString(session.mirror);
        session.baseRevision = session.mirrorRevision; session.dirty = false; session.conflicted = false;
    }
    public long revision() {
        String current = store.toJsonString(repository);
        if (!java.util.Objects.equals(current, revisionJson)) { revisionJson = current; revision++; }
        return revision;
    }
    public String authoritativeSyncJson() {
        long currentRevision = revision();
        var root = com.google.gson.JsonParser.parseString(revisionJson).getAsJsonObject();
        root.addProperty("_habitrain_config_protocol", ConfigPatch.PROTOCOL);
        root.addProperty("_habitrain_config_revision", currentRevision);
        return root.toString();
    }

    public static void setOnSaveCallback(@Nullable Runnable callback) {
        getInstance().repository().setOnSaveCallback(callback);
    }

    private ConfigManager() {
        this(new ConfigStore());
    }

    ConfigManager(ConfigStore store) {
        this.repository = new ConfigRepository();
        this.store = store;
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

    /** 由 LifecycleEventsRegistrar 在 SERVER_STARTED 注入，供运行时改 minigame 配置后 re-enforce。 */
    public void setServer(@Nullable MinecraftServer server) {
        this.currentServer = server;
        com.habitrain.core.game.sre.mode.KnifeDurabilityToggleService.applyToServer(server);
    }

    public void load() {
        ClientConfigSession session = clientSession.get();
        ConfigRepository candidate = new ConfigRepository();
        if (session == null) {
            store.load(candidate); repository = candidate; localSnapshot = candidate.copy();
        } else {
            // A disconnect reload must not clear the integrated server Store's dirty flag.
            new ConfigStore(store.getConfigFile()).load(candidate);
            session.draft.copyFrom(candidate); session.mirror = candidate.copy(); session.baseJson = store.toJsonString(candidate); session.dirty = false;
        }
    }

    public ConfigSaveResult save() {
        ClientConfigSession session = clientSession.get();
        if (session != null) {
            String json = store.toJsonString(session.draft);
            ConfigSaveResult result;
            if (session.remote) {
                result = clientSaveHandler == null ? ConfigSaveResult.failed("服务器配置提交器未就绪")
                        : clientSaveHandler.submit(session.baseJson, json, session.baseRevision);
            } else {
                if (json.equals(session.baseJson)) return ConfigSaveResult.unchanged();
                result = store.save(session.draft) ? ConfigSaveResult.saved() : ConfigSaveResult.failed("本地配置写入失败，草稿已保留");
                if (result.successful()) { repository = session.draft.copy(); localSnapshot = repository.copy(); session.mirror = session.draft.copy(); session.baseJson = json; session.dirty = false; }
            }
            return result;
        }
        ConfigSaveResult result = store.commitResult(repository);
        if (result.status() == ConfigSaveResult.Status.SAVED) {
            localSnapshot = repository.copy();
            if (!repository().isSuppressCallback()) {
                Runnable cb = repository().getOnSaveCallback();
                if (cb != null) {
                    try { cb.run(); } catch (Exception e) {
                        LOGGER.error("保存回调执行失败", e);
                    }
                }
            }
        }
        return result;
    }

    public void acknowledgeClient(String json, long acknowledgedRevision, boolean success, String submittedJson) {
        ClientConfigSession session = clientSession.get(); if (session == null) return;
        applySyncFromJson(json);
        if (success) {
            // Preserve edits made while this request was in flight.
            boolean unchangedSinceSubmit = store.toJsonString(session.draft).equals(submittedJson);
            if (!unchangedSinceSubmit) {
                var laterEdits = ConfigPatch.diff(com.google.gson.JsonParser.parseString(submittedJson).getAsJsonObject(),
                        store.buildJsonRoot(session.draft, false));
                var merged = ConfigPatch.apply(store.buildJsonRoot(session.mirror, false), laterEdits,
                        com.habitrain.core.network.ConfigUpdateScope.FULL_MOD_MENU, "");
                sync.loadFromJsonString(session.draft, merged.toString());
            }
            session.baseJson = store.toJsonString(session.mirror); session.baseRevision = acknowledgedRevision;
            if (unchangedSinceSubmit) { session.draft.copyFrom(session.mirror); session.dirty = false; }
            session.conflicted = false;
        }
    }

    public ConfigSaveResult submitPatch(com.google.gson.JsonObject patch, long baseRevision,
                                        com.habitrain.core.network.ConfigUpdateScope scope, String sceneMap) {
        if (currentServer != null && !currentServer.isSameThread()) throw new IllegalStateException("配置提交必须在服务器线程执行");
        if (baseRevision != revision()) return new ConfigSaveResult(ConfigSaveResult.Status.CONFLICT, "服务器配置已变化；草稿已保留，可在保存栏放弃草稿并重载");
        try {
            var before = store.buildJsonRoot(repository, false);
            var next = ConfigPatch.apply(before, patch, scope, sceneMap);
            if (next.toString().equals(before.toString())) return ConfigSaveResult.unchanged();
            ConfigRepository candidate = new ConfigRepository();
            if (!sync.loadFromJsonString(candidate, next.toString())) return ConfigSaveResult.failed("配置字段无效");
            ConfigRepository prepared = candidate.copy();
            ConfigRepository preparedLocalSnapshot = candidate.copy();
            prepared.setOnSaveCallback(repository.getOnSaveCallback());
            int syncBytes = store.buildJsonRoot(candidate, false).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (syncBytes > ConfigPatch.MAX_SYNC_JSON_BYTES - 256) return ConfigSaveResult.failed("完整服务器配置超过同步大小限制，请缩减配置后重试");
            if (!store.save(candidate)) return ConfigSaveResult.failed("服务器配置写入失败，权威配置未改动");
            repository = prepared; localSnapshot = preparedLocalSnapshot; revision();
            try { applyChangedModules(before, next); }
            catch (RuntimeException error) { LOGGER.error("配置已保存，但运行副作用失败", error); return new ConfigSaveResult(ConfigSaveResult.Status.SAVED, "服务器已保存配置，但运行刷新失败，请查看服务器日志"); }
            return ConfigSaveResult.saved();
        } catch (RuntimeException error) { return ConfigSaveResult.failed("配置更新被拒绝：" + error.getMessage()); }
    }

    private void applyChangedModules(com.google.gson.JsonObject before, com.google.gson.JsonObject next) {
        if (!java.util.Objects.equals(before.get("tasks"), next.get("tasks")) || !java.util.Objects.equals(before.get("gameModes"), next.get("gameModes"))) TaskPoolBuilder.invalidateAll();
        if (!java.util.Objects.equals(before.get("minigames"), next.get("minigames"))) applyMinigameEnforcement(currentServer);
        var oldGlobal = before.getAsJsonObject("global"); var newGlobal = next.getAsJsonObject("global");
        if (!java.util.Objects.equals(oldGlobal.get("knifeDurabilityEnabled"), newGlobal.get("knifeDurabilityEnabled"))) KnifeDurabilityToggleService.applyToServer(currentServer);
        if (!java.util.Objects.equals(oldGlobal.get("lobbyVoiceGroupEnabled"), newGlobal.get("lobbyVoiceGroupEnabled"))) SREGameModeBase.applyLobbyGroupToggle(currentServer);
        if (!java.util.Objects.equals(oldGlobal.get("shaderWhitelist"), newGlobal.get("shaderWhitelist")) || !java.util.Objects.equals(oldGlobal.get("shaderWhitelistEnabled"), newGlobal.get("shaderWhitelistEnabled")))
            com.habitrain.core.config.ShaderRuleRevalidation.rulesChanged(currentServer, revision());
    }

    @Override
    public TaskConfigEntry getTaskConfig(String fullId) {
        return repository().getTaskConfig(fullId);
    }

    @Override
    public boolean isTaskEnabled(String fullId, String mapName) {
        TaskConfigEntry entry = repository().getTaskConfig(fullId);
        if (entry == null) return true;
        if (!entry.enabled) return false;
        return isMapAllowed(fullId, mapName);
    }

    @Override
    public boolean isMapAllowed(String fullId, String mapName) {
        TaskConfigEntry entry = repository().getTaskConfig(fullId);
        if (entry == null) return true;
        if (entry.mapFilterMode == 0) return true;
        boolean listEmpty = entry.enabledMaps == null || entry.enabledMaps.isEmpty();
        boolean contained = !listEmpty && entry.enabledMaps.contains(mapName);
        if (entry.mapFilterMode == 1) return listEmpty || contained;
        return listEmpty || !contained;
    }

    public void setTaskConfig(String fullId, TaskConfigEntry entry) {
        repository().setTaskConfig(fullId, entry);
        markDirty();
        if (clientSession.get() == null) TaskPoolBuilder.invalidateAll();
    }

    public void putTaskConfig(String fullId, TaskConfigEntry entry) {
        repository().putTaskConfig(fullId, entry);
        markDirty();
        if (clientSession.get() == null) TaskPoolBuilder.invalidateAll();
    }

    public Map<String, TaskConfigEntry> getAllConfigs() {
        return repository().getAllConfigs();
    }

    public void setAllConfigs(Map<String, TaskConfigEntry> entries) {
        repository().setAllConfigs(entries);
        markDirty();
    }

    public Map<String, GameModeConfigScope> getAllGameModeConfigs() {
        return repository().getAllGameModeConfigs();
    }

    public long countDlcTasks() {
        return store.countDlcTasks();
    }

    public long countOriginalTasks() {
        return store.countOriginalTasks();
    }

    @Override
    public float getDlcWeightBoost() {
        return store.getDlcWeightBoost(repository());
    }

    public float getDlcProbabilityTarget() {
        return repository().getDlcProbabilityTarget();
    }

    public void setDlcProbabilityTarget(float target) {
        repository().setDlcProbabilityTarget((float) Math.max(0.1, Math.min(0.8, target)));
        markDirty();
    }

    public boolean isShaderWhitelistEnabled() {
        return repository().isShaderWhitelistEnabled();
    }

    public void setShaderWhitelistEnabled(boolean enabled) {
        repository().setShaderWhitelistEnabled(enabled);
        markDirty();
    }

    public List<String> getShaderWhitelist() {
        return repository().getShaderWhitelist();
    }

    public void setShaderWhitelist(List<String> list) {
        repository().setShaderWhitelist(list);
        markDirty();
    }

    public void setShaderWhitelistConfig(boolean enabled, List<String> list) {
        repository().setShaderWhitelistConfig(enabled, list);
        markDirty();
    }

    public void applyShaderWhitelistSync(boolean enabled, List<String> list) {
        sync.applyShaderWhitelistSync(repository(), enabled, list);
    }

    public String toJsonString() {
        return store.toJsonString(repository());
    }

    public void loadFromJsonString(String json) {
        if (!sync.loadFromJsonString(repository(), json)) return;
        markDirty();
        if (clientSession.get() == null) com.habitrain.core.game.sre.mode.KnifeDurabilityToggleService.applyToServer(currentServer);
        if (clientSession.get() == null) com.habitrain.core.game.sre.mode.SREGameModeBase.applyLobbyGroupToggle(currentServer);
        if (clientSession.get() == null) com.habitrain.core.task.TaskPoolBuilder.invalidateAll();
    }

    /**
     * @return true 合并成功并已 markDirty；false 解析失败（内存未改）
     */
    public boolean mergeFromJsonString(String json) {
        boolean ok = sync.mergeFromJsonString(repository(), json);
        if (!ok) return false;
        markDirty();
        if (clientSession.get() == null) com.habitrain.core.game.sre.mode.KnifeDurabilityToggleService.applyToServer(currentServer);
        if (clientSession.get() == null) com.habitrain.core.game.sre.mode.SREGameModeBase.applyLobbyGroupToggle(currentServer);
        if (clientSession.get() == null) com.habitrain.core.task.TaskPoolBuilder.invalidateAll();
        return true;
    }

    public void applySyncData(Map<String, TaskConfigEntry> configs, float target) {
        sync.applySyncData(repository(), configs, target);
    }

    public void applySyncFromJson(String json) {
        installClientSession();
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        ConfigRepository latest = new ConfigRepository();
        if (!sync.loadFromJsonString(latest, json)) return;
        long remoteRevision = root.has("_habitrain_config_revision") ? root.get("_habitrain_config_revision").getAsLong() : -1;
        clientSession.get().receive(latest, store.toJsonString(latest), remoteRevision);
    }

    public File getConfigFile() {
        return store.getConfigFile();
    }

    public int getSheriffCountDivisor() {
        return repository().getSheriffCountDivisor();
    }

    public void setSheriffCountDivisor(int divisor) {
        repository().setSheriffCountDivisor(divisor);
        markDirty();
    }

    public boolean isKnifeDurabilityEnabled() {
        return repository().isKnifeDurabilityEnabled();
    }

    public void setKnifeDurabilityEnabled(boolean enabled) {
        repository().setKnifeDurabilityEnabled(enabled);
        markDirty();
        if (clientSession.get() == null) com.habitrain.core.game.sre.mode.KnifeDurabilityToggleService.applyToServer(currentServer);
    }

    public boolean isLobbyVoiceGroupEnabled() {
        return repository().isLobbyVoiceGroupEnabled();
    }

    public void setLobbyVoiceGroupEnabled(boolean enabled) {
        repository().setLobbyVoiceGroupEnabled(enabled);
        markDirty();
        if (clientSession.get() == null) com.habitrain.core.game.sre.mode.SREGameModeBase.applyLobbyGroupToggle(currentServer);
    }

    public int getBlackoutGlobalCooldownSeconds() {
        return repository().getBlackoutGlobalCooldownSeconds();
    }

    public void setBlackoutGlobalCooldownSeconds(int seconds) {
        repository().setBlackoutGlobalCooldownSeconds(seconds);
        markDirty();
    }

    public boolean isBlackoutEffectEnhancementEnabled() {
        return repository().isBlackoutEffectEnhancementEnabled();
    }

    public void setBlackoutEffectEnhancementEnabled(boolean enabled) {
        repository().setBlackoutEffectEnhancementEnabled(enabled);
        markDirty();
    }

    @Override
    public MinigameConfigEntry getMinigameConfig(String minigameId) {
        return repository().getMinigameConfig(minigameId);
    }

    public MinigameConfigEntry getOrCreateMinigameConfig(String minigameId) {
        return repository().getOrCreateMinigameConfig(minigameId);
    }

    public void setMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository().setMinigameConfig(minigameId, entry);
        markDirty();
        if (clientSession.get() == null && currentServer != null) applyMinigameEnforcement(currentServer);
    }

    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository().putMinigameConfig(minigameId, entry);
        markDirty();
        if (clientSession.get() == null && currentServer != null) applyMinigameEnforcement(currentServer);
    }

    public Map<String, MinigameConfigEntry> getAllMinigameConfigs() {
        return repository().getAllMinigameConfigs();
    }

    @Override
    public boolean isMinigameGlobalEnabled() {
        return repository().isMinigameGlobalEnabled();
    }

    public void setMinigameGlobalEnabled(boolean enabled) {
        repository().setMinigameGlobalEnabled(enabled);
        markDirty();
    }

    @Override
    public boolean isMinigameEnabledForMap(String minigameId, String mapName) {
        return repository().isMinigameEnabledForMap(minigameId, mapName);
    }

    public void applyMinigameEnforcement(@Nullable MinecraftServer server) {
        if (clientSession.get() != null) return;
        if (server != null && !server.isSameThread()) throw new IllegalStateException("小游戏配置生效必须在服务器线程");
        enforcement.apply(server, repository());
    }

    public ModeMapVoteSettings getModeMapVoteSettings() {
        return repository().getModeMapVote();
    }

    public void setModeMapVoteSettings(ModeMapVoteSettings settings) {
        repository().setModeMapVote(settings);
        markDirty();
    }

    public EnvironmentSettings getEnvironmentSettings() {
        return repository().getEnvironment();
    }

    public void setEnvironmentSettings(EnvironmentSettings settings) {
        repository().setEnvironment(settings);
        markDirty();
    }

    /** Call after in-place mutation of the live EnvironmentSettings graph. */
    public void markEnvironmentDirty() {
        markDirty();
    }

    public void ensureModeMapVoteDefaults(java.util.Collection<String> modeIds,
                                          java.util.Collection<String> mapIds) {
        ModeMapVoteSettings s = repository().getModeMapVote();
        int beforeModes = s.modes.size();
        int beforeMaps = s.maps.size();
        s.ensureDefaults(modeIds, mapIds);
        if (s.modes.size() != beforeModes || s.maps.size() != beforeMaps) {
            markDirty();
        }
    }

    public boolean ensureModeMapVoteDefaultsWithInfo(java.util.Collection<String> modeIds,
                                                     java.util.Map<String, SREIntegration.DiscoveredMapInfo> mapInfoMap) {
        ModeMapVoteSettings s = repository().getModeMapVote();
        boolean changed = s.ensureMapDefaultsWithInfo(modeIds, mapInfoMap);
        if (changed) {
            markDirty();
        }
        return changed;
    }

    public boolean syncAndPruneMaps(java.util.Collection<String> modeIds,
                                    java.util.Map<String, SREIntegration.DiscoveredMapInfo> activeMaps) {
        ModeMapVoteSettings s = repository().getModeMapVote();
        boolean changed = s.syncAndPruneMaps(modeIds, activeMaps);
        if (changed) {
            markDirty();
        }
        return changed;
    }

    public boolean removeMap(String mapId) {
        ModeMapVoteSettings s = repository().getModeMapVote();
        boolean changed = s.removeMap(mapId);
        if (changed) {
            markDirty();
        }
        return changed;
    }

    public boolean refreshFromUpstreamMaps(@Nullable MinecraftServer server, boolean autoSave) {
        var discovered = SREIntegration.discoverServerMaps(server);
        boolean changed = syncAndPruneMaps(
                com.habitrain.core.api.GameModeRegistry.getAllIds(),
                discovered);
        if (changed && autoSave) {
            save();
        }
        return changed;
    }

    public SceneMotionSettings getSceneMotionSettings() {
        return repository().getSceneMotion();
    }

    public void setSceneMotionSettings(SceneMotionSettings settings) {
        repository().setSceneMotion(settings);
        markDirty();
    }

    public void markSceneMotionDirty() {
        markDirty();
    }
}
