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
 * →{@code internal.CoreSpiRegistrar} 在模组初始化时装配
 *
 * <p>未装配时一律返回安全的空值、不动作，公开层行为退化为「没有 SRE / 没有引擎」，
 * 不会 NPE（单元测试即可直接使用公开层）
 *
 * <p>2.0.11 起，下列反向依赖全部经本类切断：
 * <ul>
 *   <li>{@code api.GameModeRegistry →game.sre.ActiveModeForPlayer / SREModeStartAdapter}</li>
 *   <li>{@code api.GameModeRegistry / api.TaskRegistry →task.TaskPoolBuilder}</li>
 *   <li>{@code api.ItemReclaimHelper →io.wifi.starrailexpress.cca.ExtraSlotComponent}</li>
 *   <li>{@code api.match.MatchStateApi →game.sre.MatchStateAccess}</li>
 *   <li>{@code api.scene.* →scene.server / scene.client / scene.asset / config.ConfigManager}</li>
 *   <li>{@code api.menu.* →config.MenuGateService / client.menu.MenuAccessGuard}</li>
 *   <li>{@code api.OptionVoteApi / api.ModeMapVoteApi →vote.*}</li>
 *   <li>{@code 下游 →game.sre.EliminatedRestAreaService}。0.12 起由
 *       {@link RestAreaBridge} / {@code api.MatchRestStateApi} 取代，审核 B-01。</li>
 *   <li>{@code 下游 →game.sre.roleoverride.SreRoleOverrideResolver}。0.12 起由
 *       {@link RoleVisibilityBridge} / {@code api.role.v2.RoleVisibilityApi} 取代，审核 M-01。</li>
 * </ul>
 *
 * <h2>装配契约（审核 B14）</h2>
 * <p>2.0.10 及以前的 {@code install*} 是任意下游都能调用的公开静态方法，可以替换
 * core 自己的运行期桥接（例如让 {@code MatchStateApi.modeId()} 返回任意值，或清空桥
 * 以解开 {@code GameModeRegistry.start} 对 SRE 占用互斥保护）。现在：
 * <ul>
 *   <li>装配必须在 {@link CoreLifecycle} 作用域内进行（即 core 自己 bootstrap）；
 *   <li>每个桥接只能装配<b>一个</b>，重复装配被拒绝
 *   <li>{@link #clearForTests()} 改为包私有
 * </ul>
 *
 * <h2>桥接方法风格与硬性约定（审核 A-18）</h2>
 * <p>{@code api.spi} 下两种风格并存，<b>都是有意为之</b>，新增桥接时按下列规则选：
 * <ul>
 *   <li><b>无 {@code default} 的接口</b>（{@link SceneInstanceBridge}、{@link MenuGateBridge}
 *       {@link RestAreaBridge}、{@link RoleVisibilityBridge} 等）：可以用一个匿
 *       {@code new XxxBridge() {}} 作 NOOP 实例，且下游/测试可以直接实现
 *       这是<b>优先选择</b>。</li>
 *   <li><b>含抽象方法的接口</b>（{@link SreRuntimeBridge}、{@link TaskPoolCacheBridge}
 *       {@link ExtraSlotReclaimBridge} 等）：没有 NOOP 实例，未装配时桥接字段为 {@code null}
 *       访问器先判 {@code null} 再调用。</li>
 * </ul>
 *
 * <p><b>硬性约定（违反会引发 {@code AbstractMethodError}）</b>：任何一个桥接查询方法都
 * <b>必须</b>在访问点套 {@code try/catch(Throwable)}。原因：NOOP 实例只覆盖 {@code default}
 * 方法，一旦实现层类缺失、装配被跳过或下游传入不完整的匿名实现，
 * 调用抽象方法会抛 {@link AbstractMethodError}（属 {@link Error}，不
 * {@code RuntimeException}），裸调用会直接打断 tick / 渲染 / 网络线程
 * 本类所有访问器均遵守该约定；新增桥接查询时不要图省事省略
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
     * 未装配时的休息区桥接：{@code isResting} 返回 {@code true}（保守判定为休息中）
 * 该查询的消费方向全部是拒绝型门禁，fail-closed 才是安全方向（审核 B-01 / N-01）
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
        install("SreRuntimeBridge", SRE_RUNTIME_INSTALLED, () -> sreRuntime = bridge);
    }

    public static void installTaskPoolCache(@Nullable TaskPoolCacheBridge bridge) {
        install("TaskPoolCacheBridge", TASK_POOL_CACHE_INSTALLED, () -> taskPoolCache = bridge);
    }

    public static void installExtraSlotReclaim(@Nullable ExtraSlotReclaimBridge bridge) {
        install("ExtraSlotReclaimBridge", EXTRA_SLOT_RECLAIM_INSTALLED, () -> extraSlotReclaim = bridge);
    }

    public static void installSceneInstances(SceneInstanceBridge bridge) {
        install("SceneInstanceBridge", SCENE_INSTANCES_INSTALLED,
                () -> sceneInstances = bridge != null ? bridge : NOOP_SCENE_INSTANCES);
    }

    public static void installSceneRuntime(SceneRuntimeBridge bridge) {
        install("SceneRuntimeBridge", SCENE_RUNTIME_INSTALLED,
                () -> sceneRuntime = bridge != null ? bridge : NOOP_SCENE_RUNTIME);
    }

    public static void installSceneAssets(SceneAssetBridge bridge) {
        install("SceneAssetBridge", SCENE_ASSETS_INSTALLED,
                () -> sceneAssets = bridge != null ? bridge : NOOP_SCENE_ASSETS);
    }

    public static void installSceneCapture(SceneCaptureBridge bridge) {
        install("SceneCaptureBridge", SCENE_CAPTURE_INSTALLED,
                () -> sceneCapture = bridge != null ? bridge : NOOP_SCENE_CAPTURE);
    }

    public static void installSceneConfig(SceneConfigBridge bridge) {
        install("SceneConfigBridge", SCENE_CONFIG_INSTALLED,
                () -> sceneConfig = bridge != null ? bridge : NOOP_SCENE_CONFIG);
    }

    public static void installSceneClient(SceneClientBridge bridge) {
        install("SceneClientBridge", SCENE_CLIENT_INSTALLED,
                () -> sceneClient = bridge != null ? bridge : NOOP_SCENE_CLIENT);
    }

    public static void installMenuGate(MenuGateBridge bridge) {
        install("MenuGateBridge", MENU_GATE_INSTALLED,
                () -> menuGate = bridge != null ? bridge : NOOP_MENU_GATE);
    }

    public static void installMenuGateClient(MenuGateClientBridge bridge) {
        install("MenuGateClientBridge", MENU_GATE_CLIENT_INSTALLED,
                () -> menuGateClient = bridge != null ? bridge : NOOP_MENU_GATE_CLIENT);
    }

    public static void installVote(VoteBridge bridge) {
        install("VoteBridge", VOTE_INSTALLED, () -> vote = bridge != null ? bridge : NOOP_VOTE);
    }

    /** 淘汰休息区状态（审核 B-01：把下游的越层依赖收进公开层）。*/
    public static void installRestArea(RestAreaBridge bridge) {
        install("RestAreaBridge", REST_AREA_INSTALLED,
                () -> restArea = bridge != null ? bridge : NOOP_REST_AREA);
    }

    /** 角色解析 / 可见性（审核 M-01：让旋转选角等下游复用核心的隐藏角色过滤）。*/
    public static void installRoleVisibility(RoleVisibilityBridge bridge) {
        install("RoleVisibilityBridge", ROLE_VISIBILITY_INSTALLED,
                () -> roleVisibility = bridge != null ? bridge : NOOP_ROLE_VISIBILITY);
    }

    /**
     * 审核 B14：只允许在 core 生命周期作用域内装配，且每个桥接只能装配一次
 * 这样第三方模组无法在运行期替换 core 的桥接（例如
 * {@code MatchStateApi.modeId()} 返回任意值）
 *
     * <p><b>审核 A-01</b>：旧实现用 {@code compareAndSet(false, true)} 再 {@code apply.run()}
 * 于是「实现初始化抛异常」会留下一个<b>既已置位、又仍是 NOOP</b> 的桥接：
     * {@code isXxxInstalled()} 谎报已装配，重装被拒绝，下游所有调用静默空转且无从发现
 * 现在改为<b>执行成功后才置位</b>：失败时保持未装配状态（探针如实返回 {@code false}
 * 下游可以据此走 fail-closed 分支），并允许 bootstrap 稍后重试
 */
    private static void install(String name, AtomicBoolean installed, Runnable apply) {
        if (!CoreLifecycle.isActive()) {
            LOGGER.warn("CoreSpi.install{}() ignored: only habitrain_core bootstrap may install bridges", name);
            return;
        }
        if (installed.get()) {
            LOGGER.warn("CoreSpi.install{}() ignored: bridge already installed", name);
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

    public static boolean isSceneFullyInstalled() {
        return SCENE_INSTANCES_INSTALLED.get()
                && SCENE_RUNTIME_INSTALLED.get()
                && SCENE_ASSETS_INSTALLED.get()
                && SCENE_CAPTURE_INSTALLED.get()
                && SCENE_CONFIG_INSTALLED.get();
    }

    public static boolean isMenuGateInstalled() {
        return MENU_GATE_INSTALLED.get();
    }

    public static boolean isVoteInstalled() {
        return VOTE_INSTALLED.get();
    }

    /**
     * 休息区桥接是否已装配
 *
     * <p><b>审核 N-01 的用意</b>：本探针是「核心能力是否可用」的唯一可信来源
 * 安全相关的能力判定（例如服务端菜单门控）必须在装配缺失时<b>收紧</b>而不是放行：
     * 未装配意味着 {@code isResting} / {@code isBlocked} 只能给出保守默认值，
     * 不能被当成「检查通过」。注意本探针在 {@link #install} 失败时如实返回
 * {@code false}（审核 A-01）
 */
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

    /** 淘汰休息区状态桥接（未装配时走 fail-closed 的 NOOP）。*/
    public static RestAreaBridge restArea() {
        return restArea;
    }

    /** 角色解析 / 可见性桥接（未装配时为「不过滤」的 NOOP）。*/
    public static RoleVisibilityBridge roleVisibility() {
        return roleVisibility;
    }

    // ==================== SRE 运行时查询）====================

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

    // ==================== 休息区 / 角色可见性查询）====================

    /**
     * 玩家是否正在淘汰休息区
 *
     * <p><b>审核 B-01 / N-01</b>：读取<b>失败</b>时（实现抛异常）返回 {@code true}
     * （保守判定为休息中），因为消费方向全部是拒绝型门禁。旧实现让下游越层 import
     * 实现层类并用 {@code catch (Throwable)} 吞掉 {@code NoClassDefFoundError}
 * 方向恰好相反
 *
     * <p><b>桥接尚未装配</b>（{@code restArea == null}，即 core 自己还没走到 bootstrap
 * 时返回 {@code false}：这是「core 引擎还没起来」而不是「检查失败」，
     * 此时把每个玩家都判成休息中会让休息区语义（名牌、旁观放行等）在启动窗口内整体错乱
 * 需要「core 能力缺失即收紧」的场景请显式读 {@link #isRestAreaInstalled()}—
 * 装配<b>失败</b>时该探针如实返回 {@code false}（审核 A-01），
     * 而访问器此时会走 {@code bridge != null} 的分支并保守返回 {@code true}
 */
    public static boolean isPlayerResting(@Nullable ServerPlayer player) {
        if (player == null) return false;
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
     * 角色解析（跟随 ALIAS / REPLACE / MODIFY）。读取失败时原样返回入参
 *
     * <p>这是<b>过滤型</b>能力，不是安全门禁：失败时「不过滤」优于「全部剔除」
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
     * 角色是否可见（未被隐藏 / 未被替换）
 *
     * <p>读取失败时返回 {@code true}（不过滤）。理由同上：该判定用于候选池过滤
 * 误判为「不可见」会让池子变小甚至为空，而误判为「可见」只是退化为未过滤
 * 需要 fail-closed 的是门禁，不是候选枚举
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

    /**
     * 让某模式的任务池缓存失效
 *
     * <p>审核 M-27：{@code modeId == null} 时旧实现静默 no-op，调用方无法察觉自己
     * 传了个无关 id，可能读到脏的任务池。现在保守地退化为「全部失效」并给出 warning
 */
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

    /**
     * 回收上游额外槽位中匹配的物品
 *
     * @return {@code true} 表示移除了至少一
 */
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

    /** Test-only（包私有，见审核 B10） 还原到未装配状态）。*/
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
