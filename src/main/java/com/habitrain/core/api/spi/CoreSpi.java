package com.habitrain.core.api.spi;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.match.MatchPhase;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * 公开层的内部服务定位器（Service Provider Interface 注册点）
 *
 * <p><b>为什么存在</b>：{@code api} 包被 {@code task} / {@code game.sre} / {@code config} /
 * {@code client} 依赖，因此它<b>不能</b>反向 import 这些内部实现类。凡是公开层需
 * 「问实现层一个问题」或「通知实现层一件事」的地方，统一在本类注册接口实现，
 * 由 {@code internal.CoreSpiRegistrar} 在模组初始化时装配。</p>
 */
public final class CoreSpi {

    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|CoreSpi");

    private static final SceneInstanceBridge NOOP_SCENE_INSTANCES = new SceneInstanceBridge() {};
    private static final SceneRuntimeBridge NOOP_SCENE_RUNTIME = new SceneRuntimeBridge() {};
    private static final SceneAssetBridge NOOP_SCENE_ASSETS = new SceneAssetBridge() {};
    private static final SceneCaptureBridge NOOP_SCENE_CAPTURE = new SceneCaptureBridge() {};
    private static final SceneConfigBridge NOOP_SCENE_CONFIG = new SceneConfigBridge() {};
    private static final SceneClientBridge NOOP_SCENE_CLIENT = new SceneClientBridge() {};
    private static final MenuGateBridge NOOP_MENU_GATE = new MenuGateBridge() {};
    private static final MenuGateClientBridge NOOP_MENU_GATE_CLIENT = new MenuGateClientBridge() {};
    private static final VoteBridge NOOP_VOTE = new VoteBridge() {};
    /**
     * API-TV-01：未装配时的休息区桥接返回 {@code false}（与 {@link com.habitrain.core.api.MatchRestStateApi}
     * 公开契约一致：「桥接尚未装配时返回 false」）；而已装配桥接抛出异常时由 {@link #isPlayerResting}
     * 捕获并保守返回 {@code true}（fail-closed）。
     */
    private static final RestAreaBridge NOOP_REST_AREA = new RestAreaBridge() {};
    /** 未装配时的可见性桥接：不过滤（过滤型能力，未装配时退化为「未过滤」而非「全空」）。*/
    private static final RoleVisibilityBridge NOOP_ROLE_VISIBILITY = new RoleVisibilityBridge() {};

    private static volatile SreRuntimeBridge sreRuntime;
    private static volatile TaskPoolCacheBridge taskPoolCache;
    private static volatile ExtraSlotReclaimBridge extraSlotReclaim;

    private static volatile SceneInstanceBridge sceneInstances = NOOP_SCENE_INSTANCES;
    private static volatile SceneRuntimeBridge sceneRuntime = NOOP_SCENE_RUNTIME;
    private static volatile SceneAssetBridge sceneAssets = NOOP_SCENE_ASSETS;
    private static volatile SceneCaptureBridge sceneCapture = NOOP_SCENE_CAPTURE;
    private static volatile SceneConfigBridge sceneConfig = NOOP_SCENE_CONFIG;
    private static volatile SceneClientBridge sceneClient = NOOP_SCENE_CLIENT;
    private static volatile MenuGateBridge menuGate = NOOP_MENU_GATE;
    private static volatile MenuGateClientBridge menuGateClient = NOOP_MENU_GATE_CLIENT;
    private static volatile VoteBridge vote = NOOP_VOTE;
    private static volatile RestAreaBridge restArea = NOOP_REST_AREA;
    private static volatile RoleVisibilityBridge roleVisibility = NOOP_ROLE_VISIBILITY;

    private static final AtomicBoolean SRE_RUNTIME_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean TASK_POOL_CACHE_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean EXTRA_SLOT_RECLAIM_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean SCENE_INSTANCES_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean SCENE_RUNTIME_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean SCENE_ASSETS_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean SCENE_CAPTURE_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean SCENE_CONFIG_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean SCENE_CLIENT_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean MENU_GATE_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean MENU_GATE_CLIENT_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean VOTE_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean REST_AREA_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean ROLE_VISIBILITY_INSTALLED = new AtomicBoolean();

    private CoreSpi() {}

    // ==================== 装配（仅 habitrain_core 自身 bootstrap 调用）====================

    public static void installSreRuntime(@Nullable SreRuntimeBridge bridge) {
        installNonNull("SreRuntimeBridge", bridge, SRE_RUNTIME_INSTALLED, () -> sreRuntime = bridge);
    }

    public static void installTaskPoolCache(@Nullable TaskPoolCacheBridge bridge) {
        installNonNull("TaskPoolCacheBridge", bridge, TASK_POOL_CACHE_INSTALLED, () -> taskPoolCache = bridge);
    }

    public static void installExtraSlotReclaim(@Nullable ExtraSlotReclaimBridge bridge) {
        installNonNull("ExtraSlotReclaimBridge", bridge, EXTRA_SLOT_RECLAIM_INSTALLED, () -> extraSlotReclaim = bridge);
    }

    public static void installSceneInstances(SceneInstanceBridge bridge) {
        installNonNull("SceneInstanceBridge", bridge, SCENE_INSTANCES_INSTALLED, () -> sceneInstances = bridge);
    }

    public static void installSceneRuntime(SceneRuntimeBridge bridge) {
        installNonNull("SceneRuntimeBridge", bridge, SCENE_RUNTIME_INSTALLED, () -> sceneRuntime = bridge);
    }

    public static void installSceneAssets(SceneAssetBridge bridge) {
        installNonNull("SceneAssetBridge", bridge, SCENE_ASSETS_INSTALLED, () -> sceneAssets = bridge);
    }

    public static void installSceneCapture(SceneCaptureBridge bridge) {
        installNonNull("SceneCaptureBridge", bridge, SCENE_CAPTURE_INSTALLED, () -> sceneCapture = bridge);
    }

    public static void installSceneConfig(SceneConfigBridge bridge) {
        installNonNull("SceneConfigBridge", bridge, SCENE_CONFIG_INSTALLED, () -> sceneConfig = bridge);
    }

    public static void installSceneClient(SceneClientBridge bridge) {
        installNonNull("SceneClientBridge", bridge, SCENE_CLIENT_INSTALLED, () -> sceneClient = bridge);
    }

    public static void installMenuGate(MenuGateBridge bridge) {
        installNonNull("MenuGateBridge", bridge, MENU_GATE_INSTALLED, () -> menuGate = bridge);
    }

    public static void installMenuGateClient(MenuGateClientBridge bridge) {
        installNonNull("MenuGateClientBridge", bridge, MENU_GATE_CLIENT_INSTALLED, () -> menuGateClient = bridge);
    }

    public static void installVote(VoteBridge bridge) {
        installNonNull("VoteBridge", bridge, VOTE_INSTALLED, () -> vote = bridge);
    }

    /** 淘汰休息区状态（审核 B-01：把下游的越层依赖收进公开层）。*/
    public static void installRestArea(RestAreaBridge bridge) {
        installNonNull("RestAreaBridge", bridge, REST_AREA_INSTALLED, () -> restArea = bridge);
    }

    /** 角色解析 / 可见性（审核 M-01：让旋转选角等下游复用核心的隐藏角色过滤）。*/
    public static void installRoleVisibility(RoleVisibilityBridge bridge) {
        installNonNull("RoleVisibilityBridge", bridge, ROLE_VISIBILITY_INSTALLED, () -> roleVisibility = bridge);
    }

    /**
     * API-TV-03：拒绝装配 null 桥接（防止 INSTALLED 被置为 true 但实际仍是 NOOP/null），
     * 并保持「先判重、再判生命周期作用域、执行成功后再置位」的不变量。
     */
    private static void installNonNull(String name, @Nullable Object bridge, AtomicBoolean installed, Runnable apply) {
        if (installed.get()) {
            LOGGER.warn("CoreSpi.install{}() ignored: bridge already installed", name);
            return;
        }
        if (!CoreLifecycle.isActive()) {
            LOGGER.warn("CoreSpi.install{}() ignored: only habitrain_core bootstrap may install bridges", name);
            return;
        }
        if (bridge == null) {
            LOGGER.warn("CoreSpi.install{}() ignored: bridge must not be null", name);
            return;
        }
        try {
            apply.run();
        } catch (Throwable t) {
            LOGGER.error("CoreSpi.install{}() failed; bridge stays NOOP and reports uninstalled", name, t);
            return;
        }
        installed.set(true);
        LOGGER.debug("CoreSpi: {} installed", name);
    }

    // ==================== 装配状态（诊断 / 测试）====================

    public static boolean isSreRuntimeInstalled() {
        return SRE_RUNTIME_INSTALLED.get();
    }

    public static boolean isTaskPoolCacheInstalled() {
        return TASK_POOL_CACHE_INSTALLED.get();
    }

    public static boolean isExtraSlotReclaimInstalled() {
        return EXTRA_SLOT_RECLAIM_INSTALLED.get();
    }

    public static boolean isSceneInstancesInstalled() {
        return SCENE_INSTANCES_INSTALLED.get();
    }

    public static boolean isSceneMotionInstalled() {
        return SCENE_RUNTIME_INSTALLED.get()
                && SCENE_ASSETS_INSTALLED.get()
                && SCENE_CAPTURE_INSTALLED.get()
                && SCENE_CONFIG_INSTALLED.get();
    }

    public static boolean isSceneFullyInstalled() {
        return SCENE_INSTANCES_INSTALLED.get() && isSceneMotionInstalled();
    }

    public static boolean isMenuGateInstalled() {
        return MENU_GATE_INSTALLED.get();
    }

    public static boolean isVoteInstalled() {
        return VOTE_INSTALLED.get();
    }

    public static boolean isRestAreaInstalled() {
        return REST_AREA_INSTALLED.get();
    }

    /** 角色可见性桥接是否已装配（审核 M-01）。*/
    public static boolean isRoleVisibilityInstalled() {
        return ROLE_VISIBILITY_INSTALLED.get();
    }

    // ==================== 桥接访问器（未装配时返回安全 no-op 实现）====================

    public static SceneInstanceBridge sceneInstances() {
        return sceneInstances;
    }

    public static SceneRuntimeBridge sceneRuntime() {
        return sceneRuntime;
    }

    public static SceneAssetBridge sceneAssets() {
        return sceneAssets;
    }

    public static SceneCaptureBridge sceneCapture() {
        return sceneCapture;
    }

    public static SceneConfigBridge sceneConfig() {
        return sceneConfig;
    }

    public static SceneClientBridge sceneClient() {
        return sceneClient;
    }

    public static MenuGateBridge menuGate() {
        return menuGate;
    }

    public static MenuGateClientBridge menuGateClient() {
        return menuGateClient;
    }

    public static VoteBridge vote() {
        return vote;
    }

    /** 淘汰休息区状态桥接（未装配时返回 NOOP）。*/
    public static RestAreaBridge restArea() {
        return restArea;
    }

    /** 角色解析 / 可见性桥接（未装配时为「不过滤」的 NOOP）。*/
    public static RoleVisibilityBridge roleVisibility() {
        return roleVisibility;
    }

    // ==================== SRE 运行时查询 ====================

    public static boolean reserveMatch(ServerLevel level) {
        SreRuntimeBridge bridge = sreRuntime;
        try { return bridge == null || bridge.reserveMatch(level); }
        catch (Throwable error) { LOGGER.error("Match reservation failed", error); return false; }
    }
    public static void releaseMatch(ServerLevel level) {
        SreRuntimeBridge bridge = sreRuntime;
        if (bridge != null) {
            try { bridge.releaseMatch(level); }
            catch (Throwable error) { LOGGER.error("Match release failed", error); }
        }
    }
    public static void matchStarted(ServerLevel level) {
        SreRuntimeBridge bridge = sreRuntime;
        if (bridge != null) {
            try { bridge.matchStarted(level); }
            catch (Throwable error) { LOGGER.error("Match transition failed", error); }
        }
    }

    public static boolean isSreGameBlocking(@Nullable ServerLevel level) {
        if (level == null) return false;
        SreRuntimeBridge bridge = sreRuntime;
        if (bridge == null) return false;
        try {
            return bridge.isSreGameBlocking(level);
        } catch (Throwable t) {
            LOGGER.error("SreRuntimeBridge.isSreGameBlocking failed; refuse start", t);
            return true;
        }
    }

    public static Optional<GameMode> resolveActiveForPlayer(@Nullable ServerPlayer player) {
        if (player == null) return Optional.empty();
        SreRuntimeBridge bridge = sreRuntime;
        if (bridge == null) return Optional.empty();
        try {
            Optional<GameMode> resolved = bridge.resolveActiveForPlayer(player);
            return resolved != null ? resolved : Optional.empty();
        } catch (Throwable t) {
            LOGGER.debug("SreRuntimeBridge.resolveActiveForPlayer failed", t);
            return Optional.empty();
        }
    }

    public static MatchPhase matchPhase(@Nullable Level level) {
        if (level == null) return MatchPhase.UNKNOWN;
        SreRuntimeBridge bridge = sreRuntime;
        if (bridge == null) return MatchPhase.UNKNOWN;
        try {
            MatchPhase phase = bridge.matchPhase(level);
            return phase != null ? phase : MatchPhase.UNKNOWN;
        } catch (Throwable t) {
            LOGGER.debug("SreRuntimeBridge.matchPhase failed", t);
            return MatchPhase.UNKNOWN;
        }
    }

    public static String matchModeId(@Nullable ServerLevel level) {
        if (level == null) return "";
        SreRuntimeBridge bridge = sreRuntime;
        if (bridge == null) return "";
        try {
            String id = bridge.matchModeId(level);
            return id != null ? id : "";
        } catch (Throwable t) {
            LOGGER.debug("SreRuntimeBridge.matchModeId failed", t);
            return "";
        }
    }

    // ==================== 休息区 / 角色可见性查询 ====================

    /**
     * 玩家是否正在淘汰休息区。
     *
     * <p>API-TV-01：桥接尚未装配时返回 {@code false}；已装配桥接执行抛异常时保守返回 {@code true}（fail-closed）。</p>
     */
    public static boolean isPlayerResting(@Nullable ServerPlayer player) {
        if (player == null) return false;
        if (!REST_AREA_INSTALLED.get()) return false;
        RestAreaBridge bridge = restArea;
        if (bridge == null) return false;
        try {
            return bridge.isResting(player);
        } catch (Throwable t) {
            LOGGER.error("RestAreaBridge.isResting failed; treating player as resting (fail-closed)", t);
            return true;
        }
    }

    /**
     * 角色解析（跟随 ALIAS / REPLACE / MODIFY）。读取失败时原样返回入参。
     */
    public static SRERole resolveRoleForVisibility(SRERole role) {
        if (role == null) return null;
        RoleVisibilityBridge bridge = roleVisibility;
        if (bridge == null) return role;
        try {
            SRERole resolved = bridge.resolve(role);
            return resolved != null ? resolved : role;
        } catch (Throwable t) {
            LOGGER.debug("RoleVisibilityBridge.resolve failed; using the raw role", t);
            return role;
        }
    }

    /**
     * 角色是否可见（未被隐藏 / 未被替换）。读取失败时返回 {@code true}（不过滤）。
     */
    public static boolean isRoleVisible(SRERole role) {
        if (role == null) return false;
        RoleVisibilityBridge bridge = roleVisibility;
        if (bridge == null) return true;
        try {
            return bridge.isVisible(role);
        } catch (Throwable t) {
            LOGGER.debug("RoleVisibilityBridge.isVisible failed; keeping the role visible", t);
            return true;
        }
    }

    // ==================== 引擎查询 ====================

    public static void invalidateTaskPoolCacheAll() {
        TaskPoolCacheBridge bridge = taskPoolCache;
        if (bridge == null) return;
        try {
            bridge.invalidateAll();
        } catch (Throwable t) {
            LOGGER.error("TaskPoolCacheBridge.invalidateAll failed", t);
        }
    }

    public static void invalidateTaskPoolCache(@Nullable String modeId) {
        if (modeId == null) {
            LOGGER.warn("CoreSpi.invalidateTaskPoolCache(null): invalidating every mode pool instead");
            invalidateTaskPoolCacheAll();
            return;
        }
        TaskPoolCacheBridge bridge = taskPoolCache;
        if (bridge == null) return;
        try {
            bridge.invalidate(modeId);
        } catch (Throwable t) {
            LOGGER.error("TaskPoolCacheBridge.invalidate({}) failed", modeId, t);
        }
    }

    public static boolean reclaimExtraSlots(@Nullable Player player, Predicate<ItemStack> match) {
        if (player == null || match == null) return false;
        ExtraSlotReclaimBridge bridge = extraSlotReclaim;
        if (bridge == null) return false;
        try {
            return bridge.reclaimMatching(player, match);
        } catch (Throwable t) {
            LOGGER.debug("ExtraSlotReclaimBridge.reclaimMatching failed", t);
            return false;
        }
    }

    /** Test-only（包私有，见审核 B10 / API-TV-03）：还原全部桥接与安装标志位到未装配状态。 */
    static void clearForTests() {
        sreRuntime = null;
        taskPoolCache = null;
        extraSlotReclaim = null;
        sceneInstances = NOOP_SCENE_INSTANCES;
        sceneRuntime = NOOP_SCENE_RUNTIME;
        sceneAssets = NOOP_SCENE_ASSETS;
        sceneCapture = NOOP_SCENE_CAPTURE;
        sceneConfig = NOOP_SCENE_CONFIG;
        sceneClient = NOOP_SCENE_CLIENT;
        menuGate = NOOP_MENU_GATE;
        menuGateClient = NOOP_MENU_GATE_CLIENT;
        vote = NOOP_VOTE;
        restArea = NOOP_REST_AREA;
        roleVisibility = NOOP_ROLE_VISIBILITY;
        SRE_RUNTIME_INSTALLED.set(false);
        TASK_POOL_CACHE_INSTALLED.set(false);
        EXTRA_SLOT_RECLAIM_INSTALLED.set(false);
        MENU_GATE_INSTALLED.set(false);
        MENU_GATE_CLIENT_INSTALLED.set(false);
        VOTE_INSTALLED.set(false);
        REST_AREA_INSTALLED.set(false);
        ROLE_VISIBILITY_INSTALLED.set(false);
        SCENE_INSTANCES_INSTALLED.set(false);
        SCENE_RUNTIME_INSTALLED.set(false);
        SCENE_ASSETS_INSTALLED.set(false);
        SCENE_CAPTURE_INSTALLED.set(false);
        SCENE_CONFIG_INSTALLED.set(false);
        SCENE_CLIENT_INSTALLED.set(false);
    }
}
