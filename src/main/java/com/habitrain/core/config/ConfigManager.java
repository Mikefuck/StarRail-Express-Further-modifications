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
    private final java.util.Set<Long> clientThreadIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile ClientConfigSession sharedClientSession;
    private static volatile ClientSaveHandler clientSaveHandler;
    private final Object revisionLock = new Object();
    private volatile long serverRevision;
    private volatile String revisionJson;
    private volatile String cachedAuthoritativeJson;
    private volatile boolean serverCacheDirty = true;

    @FunctionalInterface
    public interface ClientSaveHandler { ConfigSaveResult submit(String baseJson, String draftJson, long baseRevision); }
    public static void setClientSaveHandler(ClientSaveHandler handler) { clientSaveHandler = handler; }
    private ClientConfigSession currentClientSession() {
        return clientThreadIds.contains(Thread.currentThread().threadId()) ? sharedClientSession : null;
    }
    private ConfigRepository repository() { ClientConfigSession session = currentClientSession(); return session == null ? repository : session.draft; }
    private void markDirty() {
        ClientConfigSession session = currentClientSession();
        if (session == null) {
            store.markDirty();
            serverCacheDirty = true;
        } else {
            session.dirty = true;
        }
    }
    private void refreshServerRevisionCache(@Nullable com.google.gson.JsonObject knownRoot) {
        synchronized (revisionLock) {
            com.google.gson.JsonObject root = knownRoot != null ? knownRoot.deepCopy() : store.buildJsonRoot(repository, false);
            String current = root.toString();
            if (!java.util.Objects.equals(current, revisionJson)) {
                revisionJson = current;
                serverRevision++;
            }
            root.addProperty("_habitrain_config_protocol", ConfigPatch.PROTOCOL);
            root.addProperty("_habitrain_config_revision", serverRevision);
            cachedAuthoritativeJson = root.toString();
            serverCacheDirty = false;
        }
    }
    public void installClientSession() {
        clientThreadIds.add(Thread.currentThread().threadId());
        if (sharedClientSession == null) {
            sharedClientSession = new ClientConfigSession(localSnapshot, store);
        }
    }
    public void markClientConnected() {
        clientThreadIds.add(Thread.currentThread().threadId());
        ClientConfigSession s = new ClientConfigSession(localSnapshot, store);
        s.remote = true;
        s.baseRevision = -1;
        sharedClientSession = s;
    }
    public void clearClientSession() {
        if (clientThreadIds.isEmpty()) {
            clientThreadIds.add(Thread.currentThread().threadId());
        }
        sharedClientSession = new ClientConfigSession(localSnapshot, store);
    }
    public void beginClientEditing() {
        installClientSession(); ClientConfigSession session = currentClientSession();
        if (!session.editing && !session.dirty) {
            session.draft.copyFrom(session.mirror); session.baseJson = store.toJsonString(session.draft);
            session.baseRevision = session.mirrorRevision;
        }
        session.editing = true;
    }
    public void endClientEditing() { ClientConfigSession session = currentClientSession(); if (session != null) session.editing = false; }
    public boolean hasClientConfigConflict() { ClientConfigSession session = currentClientSession(); return session != null && session.conflicted; }
    public void markClientConfigConflict() { ClientConfigSession session = currentClientSession(); if (session != null) session.conflicted = true; }
    public void reloadClientDraft() {
        ClientConfigSession session = currentClientSession(); if (session == null) return;
        session.draft.copyFrom(session.mirror); session.baseJson = store.toJsonString(session.mirror);
        session.baseRevision = session.mirrorRevision; session.dirty = false; session.conflicted = false;
    }
    public long revision() {
        if (serverCacheDirty || cachedAuthoritativeJson == null) {
            synchronized (revisionLock) {
                if (serverCacheDirty || cachedAuthoritativeJson == null) {
                    refreshServerRevisionCache(null);
                }
            }
        }
        return serverRevision;
    }
    public String authoritativeSyncJson() {
        if (serverCacheDirty || cachedAuthoritativeJson == null) {
            synchronized (revisionLock) {
                if (serverCacheDirty || cachedAuthoritativeJson == null) {
                    refreshServerRevisionCache(null);
                }
            }
        }
        return cachedAuthoritativeJson;
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
        ClientConfigSession session = currentClientSession();
        ConfigRepository candidate = new ConfigRepository();
        if (session == null) {
            store.load(candidate); repository = candidate; localSnapshot = candidate.copy();
            refreshServerRevisionCache(null);
            ClientConfigSession shared = sharedClientSession;
            if (shared != null && !shared.remote) {
                shared.draft.copyFrom(candidate);
                shared.mirror = candidate.copy();
                shared.baseJson = store.toJsonString(candidate);
                shared.dirty = false;
                shared.conflicted = false;
            }
        } else {
            // A disconnect reload must not clear the integrated server Store's dirty flag.
            new ConfigStore(store.getConfigFile()).load(candidate);
            session.draft.copyFrom(candidate); session.mirror = candidate.copy(); session.baseJson = store.toJsonString(candidate); session.dirty = false; session.conflicted = false;
        }
    }

    public ConfigSaveResult save() {
        ClientConfigSession session = currentClientSession();
        if (session != null) {
            String json = store.toJsonString(session.draft);
            ConfigSaveResult result;
            if (session.remote) {
                result = clientSaveHandler == null ? ConfigSaveResult.failed("服务器配置提交器未就绪")
                        : clientSaveHandler.submit(session.baseJson, json, session.baseRevision);
            } else {
                if (json.equals(session.baseJson)) return ConfigSaveResult.unchanged();
                result = store.save(session.draft) ? ConfigSaveResult.saved() : ConfigSaveResult.failed("本地配置写入失败，草稿已保留");
                if (result.successful()) {
                    repository = session.draft.copy();
                    localSnapshot = repository.copy();
                    session.mirror = session.draft.copy();
                    session.baseJson = json;
                    session.dirty = false;
                    refreshServerRevisionCache(null);
                }
            }
            return result;
        }
        ConfigSaveResult result = store.commitResult(repository);
        if (result.status() == ConfigSaveResult.Status.SAVED) {
            localSnapshot = repository.copy();
            refreshServerRevisionCache(null);
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
        ClientConfigSession session = currentClientSession(); if (session == null) return;
        if (json != null && !json.isBlank() && !"{}".equals(json.trim()) && acknowledgedRevision >= 0) {
            applySyncFromJson(json);
        }
        if (success) {
            // Preserve edits made while this request was in flight.
            boolean unchangedSinceSubmit = store.toJsonString(session.draft).equals(submittedJson);
            if (!unchangedSinceSubmit && submittedJson != null) {
                try {
                    var laterEdits = ConfigPatch.diff(com.google.gson.JsonParser.parseString(submittedJson).getAsJsonObject(),
                            store.buildJsonRoot(session.draft, false));
                    var merged = ConfigPatch.apply(store.buildJsonRoot(session.mirror, false), laterEdits,
                            com.habitrain.core.network.ConfigUpdateScope.FULL_MOD_MENU, "");
                    sync.loadFromJsonObject(session.draft, merged, false);
                } catch (RuntimeException e) {
                    LOGGER.warn("合并在途配置编辑失败，保留当前草稿", e);
                }
            }
            session.baseJson = store.toJsonString(session.mirror); session.baseRevision = acknowledgedRevision;
            if (unchangedSinceSubmit) { session.draft.copyFrom(session.mirror); session.dirty = false; }
            session.conflicted = false;
        }
    }

    public ConfigSaveResult submitPatch(com.google.gson.JsonObject patch, long baseRevision,
                                        com.habitrain.core.network.ConfigUpdateScope scope, String sceneMap) {
        return submitPatch(patch, baseRevision, scope, sceneMap, false);
    }

    public java.util.Set<String> allowedSceneMapKeys(@Nullable net.minecraft.server.level.ServerLevel level) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(repository.getModeMapVote().maps.keySet());
        keys.addAll(repository.getSceneMotion().configuredEditorMapKeys());
        MinecraftServer server = currentServer != null ? currentServer : (level != null ? level.getServer() : null);
        if (server != null && server.overworld() != null) {
            for (String mapId : com.habitrain.core.game.sre.mode.SREModeStartAdapter.getAvailableMaps(server.overworld())) {
                if (mapId != null && !mapId.isBlank() && !SREIntegration.isReservedMapId(mapId)) {
                    keys.add(mapId.trim());
                }
            }
        }
        if (level != null) {
            String contextMapKey = com.habitrain.core.game.sre.scene.SreSceneContextResolver.INSTANCE.resolve(level).mapKey();
            if (contextMapKey != null && !contextMapKey.isBlank()) {
                keys.add(contextMapKey.trim());
            }
        }
        return keys;
    }

    public ConfigSaveResult submitPatch(com.google.gson.JsonObject patch, long baseRevision,
                                        com.habitrain.core.network.ConfigUpdateScope scope, String sceneMap,
                                        boolean menuGateBlocked) {
        if (currentServer != null && !currentServer.isSameThread()) throw new IllegalStateException("配置提交必须在服务器线程执行");
        if (baseRevision != revision()) return new ConfigSaveResult(ConfigSaveResult.Status.CONFLICT, "服务器配置已变化；草稿已保留，可在保存栏放弃草稿并重载");
        try {
            var before = store.buildJsonRoot(repository, false);
            var next = ConfigPatch.apply(before, patch, scope, sceneMap);
            com.habitrain.core.network.ConfigUpdateAccessPolicy.validateMenuGateScopedMutation(before, next, scope, menuGateBlocked);
            if (scope == com.habitrain.core.network.ConfigUpdateScope.ADMIN_SCENE_TOOL) {
                var allowed = allowedSceneMapKeys(null);
                if (sceneMap != null && !sceneMap.isBlank()) allowed.add(sceneMap.trim());
                com.habitrain.core.network.ConfigUpdateAccessPolicy.validateAdminSceneMapKeys(before, next, scope, allowed);
            }
            if (next.equals(before)) return ConfigSaveResult.unchanged();
            ConfigRepository candidate = new ConfigRepository();
            if (!sync.loadFromJsonObject(candidate, next, true)) return ConfigSaveResult.failed("配置字段无效");
            ConfigRepository prepared = candidate.copy();
            ConfigRepository preparedLocalSnapshot = candidate.copy();
            prepared.setOnSaveCallback(repository.getOnSaveCallback());
            com.google.gson.JsonObject authoritativeRoot = store.buildJsonRoot(prepared, false);
            int syncBytes = authoritativeRoot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (syncBytes > ConfigPatch.MAX_SYNC_JSON_BYTES - 256) return ConfigSaveResult.failed("完整服务器配置超过同步大小限制，请缩减配置后重试");
            if (!store.save(candidate)) return ConfigSaveResult.failed("服务器配置写入失败，权威配置未改动");
            repository = prepared; localSnapshot = preparedLocalSnapshot;
            refreshServerRevisionCache(authoritativeRoot);
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
        return entry.isAllowedOnMap(mapName);
    }

    public void setTaskConfig(String fullId, TaskConfigEntry entry) {
        repository().setTaskConfig(fullId, entry);
        markDirty();
        if (currentClientSession() == null) TaskPoolBuilder.invalidateAll();
    }

    public void putTaskConfig(String fullId, TaskConfigEntry entry) {
        repository().putTaskConfig(fullId, entry);
        markDirty();
        if (currentClientSession() == null) TaskPoolBuilder.invalidateAll();
    }

    public Map<String, TaskConfigEntry> getAllConfigs() {
        return repository().getAllConfigs();
    }

    public void setAllConfigs(Map<String, TaskConfigEntry> entries) {
        repository().setAllConfigs(entries);
        markDirty();
    }

    public GameModeConfigScope getGameModeConfig(String gameModeId) {
        return repository().getGameModeConfig(gameModeId);
    }

    public GameModeConfigScope getOrCreateGameModeConfig(String gameModeId) {
        return repository().getOrCreateGameModeConfig(gameModeId);
    }

    public void setGameModeConfig(String gameModeId, GameModeConfigScope scope) {
        repository().setGameModeConfig(gameModeId, scope);
        markDirty();
        if (currentClientSession() == null) TaskPoolBuilder.invalidateAll();
    }

    public boolean isGameModeEnabled(String gameModeId) {
        GameModeConfigScope scope = repository().getGameModeConfig(gameModeId);
        return scope == null || scope.isEnabled();
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
        ClientConfigSession session = currentClientSession();
        if (session != null) {
            sync.applyShaderWhitelistSync(session.mirror, enabled, list);
            session.remote = true;
            if (!session.editing && !session.dirty) {
                sync.applyShaderWhitelistSync(session.draft, enabled, list);
                session.baseJson = store.toJsonString(session.draft);
            }
        } else {
            sync.applyShaderWhitelistSync(repository, enabled, list);
            refreshServerRevisionCache(null);
        }
    }

    public String toJsonString() {
        return store.toJsonString(repository());
    }

    public void loadFromJsonString(String json) {
        if (!sync.loadFromJsonString(repository(), json)) return;
        markDirty();
        if (currentClientSession() == null) {
            refreshServerRevisionCache(null);
            com.habitrain.core.game.sre.mode.KnifeDurabilityToggleService.applyToServer(currentServer);
            com.habitrain.core.game.sre.mode.SREGameModeBase.applyLobbyGroupToggle(currentServer);
            com.habitrain.core.task.TaskPoolBuilder.invalidateAll();
        }
    }

    /**
     * @return true 合并成功并已 markDirty；false 解析失败（内存未改）
     */
    public boolean mergeFromJsonString(String json) {
        boolean ok = sync.mergeFromJsonString(repository(), json);
        if (!ok) return false;
        markDirty();
        if (currentClientSession() == null) {
            refreshServerRevisionCache(null);
            com.habitrain.core.game.sre.mode.KnifeDurabilityToggleService.applyToServer(currentServer);
            com.habitrain.core.game.sre.mode.SREGameModeBase.applyLobbyGroupToggle(currentServer);
            com.habitrain.core.task.TaskPoolBuilder.invalidateAll();
        }
        return true;
    }

    public void applySyncData(Map<String, TaskConfigEntry> configs, float target) {
        applySyncData(configs, com.habitrain.core.network.TaskConfigPayload.pollLatestMinigames(), target);
    }

    public void applySyncData(Map<String, TaskConfigEntry> configs, Map<String, MinigameConfigEntry> minigames, float target) {
        ClientConfigSession session = currentClientSession();
        if (session != null) {
            sync.applySyncData(session.mirror, configs, minigames, target);
            session.remote = true;
            if (!session.editing && !session.dirty) {
                sync.applySyncData(session.draft, configs, minigames, target);
                session.baseJson = store.toJsonString(session.draft);
            }
        } else {
            sync.applySyncData(repository, configs, minigames, target);
            refreshServerRevisionCache(null);
        }
    }

    public void applySyncFromJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) return;
        installClientSession();
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        long remoteRevision = root.has("_habitrain_config_revision") ? root.get("_habitrain_config_revision").getAsLong() : -1;
        if (root.has("_habitrain_config_revision") && remoteRevision < 0) return;
        ConfigRepository latest = new ConfigRepository();
        if (!sync.loadFromJsonObject(latest, root, false)) return;
        currentClientSession().receive(latest, store.toJsonString(latest), remoteRevision);
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
        if (currentClientSession() == null) com.habitrain.core.game.sre.mode.KnifeDurabilityToggleService.applyToServer(currentServer);
    }

    public boolean isLobbyVoiceGroupEnabled() {
        return repository().isLobbyVoiceGroupEnabled();
    }

    public void setLobbyVoiceGroupEnabled(boolean enabled) {
        repository().setLobbyVoiceGroupEnabled(enabled);
        markDirty();
        if (currentClientSession() == null) com.habitrain.core.game.sre.mode.SREGameModeBase.applyLobbyGroupToggle(currentServer);
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
        if (currentClientSession() == null && currentServer != null) applyMinigameEnforcement(currentServer);
    }

    public void putMinigameConfig(String minigameId, MinigameConfigEntry entry) {
        repository().putMinigameConfig(minigameId, entry);
        markDirty();
        if (currentClientSession() == null && currentServer != null) applyMinigameEnforcement(currentServer);
    }

    public Map<String, MinigameConfigEntry> getAllMinigameConfigs() {
        return repository().getAllMinigameConfigs();
    }

    public void setMinigames(Map<String, MinigameConfigEntry> entries) {
        ClientConfigSession session = currentClientSession();
        if (session != null) {
            session.mirror.setMinigames(entries);
            if (!session.editing && !session.dirty) {
                session.draft.setMinigames(entries);
                session.baseJson = store.toJsonString(session.draft);
            }
        } else {
            repository.setMinigames(entries);
            refreshServerRevisionCache(null);
            if (currentServer != null) applyMinigameEnforcement(currentServer);
        }
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
        if (currentClientSession() != null) return;
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

    /** Server upload completion: persist its path and restore the live graph if saving fails. */
    public ConfigSaveResult saveMapVotePreviewPath(String mapId, String path) {
        if (currentClientSession() != null) return ConfigSaveResult.failed("预览图路径只能由服务端保存");
        ModeMapVoteSettings settings = getModeMapVoteSettings();
        MapVoteEntry entry = settings.maps.get(mapId);
        if (entry == null) return ConfigSaveResult.failed("地图已不存在");
        MapVoteProfileSettings previousProfile = entry.profile;
        String previousPath = previousProfile == null ? null : previousProfile.previewPath;
        if (previousProfile != null && java.util.Objects.equals(previousPath, path)) return ConfigSaveResult.unchanged();
        if (entry.profile == null) entry.profile = new MapVoteProfileSettings();
        entry.profile.previewPath = path;
        setModeMapVoteSettings(settings);
        boolean saved = false;
        try {
            ConfigSaveResult result = save();
            saved = result.successful();
            return result;
        } finally {
            if (!saved) {
                entry.profile = previousProfile;
                if (previousProfile != null) previousProfile.previewPath = previousPath;
                setModeMapVoteSettings(settings);
            }
        }
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
