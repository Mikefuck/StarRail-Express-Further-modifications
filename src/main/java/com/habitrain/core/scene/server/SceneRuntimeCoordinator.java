package com.habitrain.core.scene.server;

import com.habitrain.core.api.match.MatchEvents;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.SceneMotionSettings;
import com.habitrain.core.game.sre.scene.SreSceneContextResolver;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneBackgroundKey;
import com.habitrain.core.scene.model.SceneRuntimeState;
import com.habitrain.core.scene.network.SceneAdditionalRuntimeStatesS2C;
import com.habitrain.core.scene.network.SceneAssetManifestS2C;
import com.habitrain.core.scene.network.SceneRuntimeStateS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景运行协调器（监听对局生命周期事件，下发开局运行状态与资产 Manifest）。
 */
public final class SceneRuntimeCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneRuntimeCoordinator.class.getSimpleName());

    private static final SceneRuntimeCoordinator INSTANCE = new SceneRuntimeCoordinator();

    public static SceneRuntimeCoordinator getInstance() {
        return INSTANCE;
    }

    private SceneContextResolver contextResolver = SreSceneContextResolver.INSTANCE;
    private final Map<String, SceneRuntimeState> levelRuntimeStates = new ConcurrentHashMap<>();
    private final Map<String, List<SceneRuntimeState>> levelAdditionalRuntimeStates = new ConcurrentHashMap<>();
    private final AtomicInteger profileRevision = new AtomicInteger(1);

    private SceneRuntimeCoordinator() {}

    public void setContextResolver(SceneContextResolver resolver) {
        this.contextResolver = resolver != null ? resolver : SreSceneContextResolver.INSTANCE;
    }

    public void init() {
        MatchEvents.STARTED.register(this::onMatchStarted);
        MatchEvents.ROUND_ENDED.register((level, settlement) -> onMatchEnded(level));
        LOGGER.info("移动场景运行协调器已初始化并监听 MatchEvents");
    }

    public void onMatchStarted(ServerLevel level) {
        if (level == null) return;
        SceneMotionSettings settings = ConfigManager.getInstance().getSceneMotionSettings();
        if (!settings.enabled) {
            LOGGER.debug("场景系统已全局禁用，跳过开局启动");
            return;
        }

        SceneContextResolver.SceneContext ctx = contextResolver.resolve(level);
        String mapKey = ctx.mapKey();
        SceneProfile profile = settings.getProfile(mapKey);

        boolean anyBackgroundEnabled = settings.getResolvedBackgrounds(mapKey).stream()
                .anyMatch(background -> background.profile().isEnabled());
        if (!anyBackgroundEnabled && !profile.getOutsideSound().isEnabled() && !profile.getShake().isEnabled()) {
            LOGGER.debug("地图 {} 的移动场景未启用", mapKey);
            return;
        }

        SceneAssetDescriptor descriptor = profile.isEnabled()
                ? SceneAssetStore.getInstance().getDescriptor(mapKey) : null;
        String assetHash = descriptor != null ? descriptor.sha256() : "";

        if (profile.isEnabled() && (descriptor == null || !descriptor.isValid())) {
            ServerPlayer progressRecipient = level.players().stream()
                    .filter(player -> player.hasPermissions(2))
                    .findFirst()
                    .orElse(level.players().stream().findFirst().orElse(null));
            if (progressRecipient == null) {
                LOGGER.warn("地图 {} 已启用移动场景，但没有资产且当前无玩家，无法自动生成", mapKey);
            } else if (profile.getSourceBounds().isEmpty()) {
                LOGGER.warn("地图 {} 已启用移动场景，但没有资产且源选区为空", mapKey);
                progressRecipient.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§c[移动场景] 当前地图没有场景资产，且源选区为空；请用配置器重新选区"));
            } else {
                boolean captureStarted = SceneCaptureService.getInstance()
                        .requestCapture(level, mapKey, profile.getSourceBounds(), progressRecipient);
                if (captureStarted) {
                    LOGGER.warn("地图 {} 缺少移动场景资产，已从保存的源选区自动开始生成；完成后将热更新当前对局", mapKey);
                } else {
                    LOGGER.warn("地图 {} 缺少移动场景资产，自动生成未能启动", mapKey);
                }
            }
        }

        long startTime = level.getGameTime();
        int rev = profileRevision.incrementAndGet();
        SceneRuntimeState state = new SceneRuntimeState(true, startTime, rev, mapKey, assetHash, profile.copy());

        String dimKey = level.dimension().location().toString();
        levelRuntimeStates.put(dimKey, state);
        List<SceneRuntimeState> additionalStates = createAdditionalStates(
                settings, mapKey, startTime, level);
        levelAdditionalRuntimeStates.put(dimKey, additionalStates);

        LOGGER.info("移动场景启动: mapKey={}, startTime={}, assetHash={}", mapKey, startTime, descriptor != null ? descriptor.shortHash() : "NONE");

        // 广播 Manifest 与运行状态包
        if (descriptor != null && descriptor.isValid()) {
            SceneAssetManifestS2C manifestPayload = new SceneAssetManifestS2C(mapKey, descriptor);
            for (ServerPlayer player : level.players()) {
                SceneTransferService.getInstance().authorize(player.getUUID(), descriptor.sha256(), descriptor.compressedSize());
                ServerPlayNetworking.send(player, manifestPayload);
            }
        }

        SceneRuntimeStateS2C statePayload = new SceneRuntimeStateS2C(state);
        for (ServerPlayer player : level.players()) {
            ServerPlayNetworking.send(player, statePayload);
            ServerPlayNetworking.send(player, new SceneAdditionalRuntimeStatesS2C(additionalStates));
        }
    }

    public void onMatchEnded(ServerLevel level) {
        if (level == null) return;
        ScenePreloadCoordinator.getInstance().reset(level);
        String dimKey = level.dimension().location().toString();
        SceneRuntimeState oldState = levelRuntimeStates.remove(dimKey);
        levelAdditionalRuntimeStates.remove(dimKey);
        if (oldState != null && oldState.isActive()) {
            LOGGER.info("移动场景停止: dimension={}", dimKey);
            SceneRuntimeStateS2C inactivePayload = new SceneRuntimeStateS2C(SceneRuntimeState.INACTIVE);
            for (ServerPlayer player : level.players()) {
                ServerPlayNetworking.send(player, inactivePayload);
                ServerPlayNetworking.send(player, new SceneAdditionalRuntimeStatesS2C(List.of()));
            }
        }
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (player == null || player.serverLevel() == null) return;
        ServerLevel level = player.serverLevel();
        String dimKey = level.dimension().location().toString();
        SceneRuntimeState state = levelRuntimeStates.get(dimKey);

        if (state != null && state.isActive()) {
            SceneAssetDescriptor descriptor = SceneAssetStore.getInstance().getDescriptor(state.getMapKey());
            if (descriptor != null && descriptor.isValid()) {
                SceneTransferService.getInstance().authorize(player.getUUID(), descriptor.sha256(), descriptor.compressedSize());
                ServerPlayNetworking.send(player, new SceneAssetManifestS2C(state.getMapKey(), descriptor));
            }
            ServerPlayNetworking.send(player, new SceneRuntimeStateS2C(state));
            List<SceneRuntimeState> additional = levelAdditionalRuntimeStates.getOrDefault(dimKey, List.of());
            for (SceneRuntimeState additionalState : additional) {
                SceneAssetDescriptor additionalDescriptor = SceneAssetStore.getInstance()
                        .getDescriptor(additionalState.getMapKey());
                if (additionalDescriptor != null && additionalDescriptor.isValid()) {
                    SceneTransferService.getInstance().authorize(player.getUUID(),
                            additionalDescriptor.sha256(), additionalDescriptor.compressedSize());
                    ServerPlayNetworking.send(player,
                            new SceneAssetManifestS2C(additionalState.getMapKey(), additionalDescriptor));
                }
            }
            ServerPlayNetworking.send(player, new SceneAdditionalRuntimeStatesS2C(additional));
        }
    }

    public void onAssetPublished(MinecraftServer server, String mapKey, SceneAssetDescriptor descriptor) {
        if (server == null || descriptor == null) return;
        SceneAssetManifestS2C payload = new SceneAssetManifestS2C(mapKey, descriptor);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            SceneTransferService.getInstance().authorize(player.getUUID(), descriptor.sha256(), descriptor.compressedSize());
            ServerPlayNetworking.send(player, payload);
        }

        for (ServerLevel level : server.getAllLevels()) {
            String dimensionKey = level.dimension().location().toString();
            SceneRuntimeState current = levelRuntimeStates.get(dimensionKey);
            if (current == null || !current.isActive() || !Objects.equals(current.getMapKey(), mapKey)) {
                continue;
            }

            SceneRuntimeState refreshed = new SceneRuntimeState(
                    true,
                    current.getStartGameTime(),
                    profileRevision.incrementAndGet(),
                    current.getMapKey(),
                    descriptor.sha256(),
                    current.getProfile().copy()
            );
            levelRuntimeStates.put(dimensionKey, refreshed);
            SceneRuntimeStateS2C statePayload = new SceneRuntimeStateS2C(refreshed);
            for (ServerPlayer player : level.players()) {
                ServerPlayNetworking.send(player, statePayload);
            }
            LOGGER.info("移动场景资产已热更新到当前对局: mapKey={}, dimension={}, assetHash={}",
                    mapKey, dimensionKey, descriptor.shortHash());
        }
        for (ServerLevel level : server.getAllLevels()) {
            String dimensionKey = level.dimension().location().toString();
            List<SceneRuntimeState> currentAdditional = levelAdditionalRuntimeStates.get(dimensionKey);
            if (currentAdditional == null || currentAdditional.isEmpty()) continue;
            boolean changed = false;
            List<SceneRuntimeState> refreshed = new ArrayList<>(currentAdditional.size());
            for (SceneRuntimeState current : currentAdditional) {
                if (Objects.equals(current.getMapKey(), mapKey)) {
                    refreshed.add(new SceneRuntimeState(true, current.getStartGameTime(),
                            profileRevision.incrementAndGet(), current.getMapKey(), descriptor.sha256(),
                            current.getProfile().copy()));
                    changed = true;
                } else {
                    refreshed.add(current);
                }
            }
            if (changed) {
                levelAdditionalRuntimeStates.put(dimensionKey, List.copyOf(refreshed));
                for (ServerPlayer player : level.players()) {
                    ServerPlayNetworking.send(player, new SceneAdditionalRuntimeStatesS2C(refreshed));
                }
            }
        }
    }

    /**
     * 手动激活场景（命令 / API）。
     */
    public boolean activate(ServerLevel level, String mapKey) {
        if (level == null) return false;
        SceneMotionSettings settings = ConfigManager.getInstance().getSceneMotionSettings();
        if (mapKey == null || mapKey.isBlank()) {
            SceneContextResolver.SceneContext ctx = contextResolver.resolve(level);
            mapKey = ctx.mapKey();
        }

        SceneProfile profile = settings.getProfile(mapKey);
        SceneAssetDescriptor descriptor = SceneAssetStore.getInstance().getDescriptor(mapKey);
        String assetHash = descriptor != null ? descriptor.sha256() : "";

        long startTime = level.getGameTime();
        int rev = profileRevision.incrementAndGet();
        SceneRuntimeState state = new SceneRuntimeState(true, startTime, rev, mapKey, assetHash, profile.copy());

        String dimKey = level.dimension().location().toString();
        levelRuntimeStates.put(dimKey, state);
        List<SceneRuntimeState> additionalStates = createAdditionalStates(settings, mapKey, startTime, level);
        levelAdditionalRuntimeStates.put(dimKey, additionalStates);

        if (descriptor != null && descriptor.isValid()) {
            SceneAssetManifestS2C manifestPayload = new SceneAssetManifestS2C(mapKey, descriptor);
            for (ServerPlayer player : level.players()) {
                SceneTransferService.getInstance().authorize(player.getUUID(), descriptor.sha256(), descriptor.compressedSize());
                ServerPlayNetworking.send(player, manifestPayload);
            }
        }

        SceneRuntimeStateS2C statePayload = new SceneRuntimeStateS2C(state);
        for (ServerPlayer player : level.players()) {
            ServerPlayNetworking.send(player, statePayload);
            ServerPlayNetworking.send(player, new SceneAdditionalRuntimeStatesS2C(additionalStates));
        }
        return true;
    }

    /**
     * 手动停用场景。
     */
    public boolean deactivate(ServerLevel level) {
        if (level == null) return false;
        onMatchEnded(level);
        return true;
    }

    public SceneRuntimeState getRuntimeState(ServerLevel level) {
        if (level == null) return SceneRuntimeState.INACTIVE;
        String dimKey = level.dimension().location().toString();
        return levelRuntimeStates.getOrDefault(dimKey, SceneRuntimeState.INACTIVE);
    }

    /** 换维度时明确清除旧状态，再同步目标维度状态/Manifest。 */
    public void onPlayerChangeDimension(ServerPlayer player) {
        if (player == null) return;
        SceneTransferService.getInstance().onPlayerDisconnect(player.getUUID());
        ServerPlayNetworking.send(player, new SceneRuntimeStateS2C(SceneRuntimeState.INACTIVE));
        ServerPlayNetworking.send(player, new SceneAdditionalRuntimeStatesS2C(List.of()));
        onPlayerJoin(player);
    }

    public void resetAll() {
        levelRuntimeStates.clear();
        levelAdditionalRuntimeStates.clear();
    }

    private List<SceneRuntimeState> createAdditionalStates(SceneMotionSettings settings, String mapKey,
                                                           long startTime, ServerLevel level) {
        List<SceneRuntimeState> states = new ArrayList<>();
        for (SceneMotionSettings.ResolvedBackground background : settings.getResolvedBackgrounds(mapKey)) {
            if (background.fallback() || !background.profile().isEnabled()) continue;
            String assetKey = SceneBackgroundKey.assetKey(mapKey, background.id());
            SceneAssetDescriptor descriptor = SceneAssetStore.getInstance().getDescriptor(assetKey);
            String hash = descriptor != null && descriptor.isValid() ? descriptor.sha256() : "";
            SceneRuntimeState state = new SceneRuntimeState(true, startTime,
                    profileRevision.incrementAndGet(), assetKey, hash, background.profile().copy());
            states.add(state);
            if (descriptor == null || !descriptor.isValid()) {
                LOGGER.warn("地图 {} 的附加动态背景 {} 已启用但缺少资产；请用配置器生成", mapKey, background.name());
                continue;
            }
            SceneAssetManifestS2C manifest = new SceneAssetManifestS2C(assetKey, descriptor);
            for (ServerPlayer player : level.players()) {
                SceneTransferService.getInstance().authorize(player.getUUID(), descriptor.sha256(), descriptor.compressedSize());
                ServerPlayNetworking.send(player, manifest);
            }
        }
        return List.copyOf(states);
    }
}
