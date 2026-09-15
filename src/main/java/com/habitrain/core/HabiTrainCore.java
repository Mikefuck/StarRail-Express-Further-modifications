package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.betel.BetelFoodRestriction;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelQuestDefinition;
import com.habitrain.core.betel.BetelQuestState;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.sre.EnvironmentController;
import com.habitrain.core.game.sre.EliminatedRestAreaService;
import com.habitrain.core.game.sre.MvpScoreTracker;
import com.habitrain.core.game.sre.SREGameStateProvider;
import com.habitrain.core.game.sre.SREMurderMode;
import com.habitrain.core.game.sre.SREOriginalModeBridge;
import com.habitrain.core.game.sre.SRERepairMode;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.task.BackpackSearchHandler;
import com.habitrain.core.task.ClearableHandlerRegistry;
import com.habitrain.core.task.SlownessReapplyManager;
import com.habitrain.core.task.TaskManager;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 哈比列车核心 — 主入口类。
 * 职责: 配置初始化、GameMode注册，并委托细分职责给 5 个专业注册器/服务类。
 * <ul>
 *   <li>{@link NetworkRegistrar} — 网络数据包类型注册</li>
 *   <li>{@link CommandRegistrar} — 命令注册</li>
 *   <li>{@link LifecycleEventsRegistrar} — 生命周期事件</li>
 *   <li>{@link C2SReceiverRegistrar} — C2S 数据包接收器</li>
 *   <li>{@link VoiceGroupService} — 语音群组服务（/instantgroup）</li>
 * </ul>
 */
public class HabiTrainCore implements ModInitializer {
    public static final String MOD_ID = "habitrain_core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ===== 音效事件常量 =====
    public static final ResourceLocation BETEL_NUT_EAT_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "betel_nut_eat");
    public static final SoundEvent BETEL_NUT_EAT_SOUND = SoundEvent.createVariableRangeEvent(BETEL_NUT_EAT_ID);
    public static final ResourceLocation BETEL_NUT_GET_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "betel_nut_get");
    public static final SoundEvent BETEL_NUT_GET_SOUND = SoundEvent.createVariableRangeEvent(BETEL_NUT_GET_ID);
    public static final ResourceLocation LOOK_MY_EYES_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "look_my_eyes");
    public static final SoundEvent LOOK_MY_EYES_SOUND = SoundEvent.createVariableRangeEvent(LOOK_MY_EYES_ID);
    // look_my_eyes.ogg now bundled in assets
    public static final ResourceLocation BACKPACK_SEARCH_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "backpack_search");
    public static final SoundEvent BACKPACK_SEARCH_SOUND = SoundEvent.createVariableRangeEvent(BACKPACK_SEARCH_ID);
    public static final ResourceLocation MIKE_CODE_EDIT_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "mike_code_edit");
    public static final SoundEvent MIKE_CODE_EDIT_SOUND = SoundEvent.createVariableRangeEvent(MIKE_CODE_EDIT_ID);

    // ===== 任务 ID 常量（全限定字符串，供 mixin/追踪器按 getFullId() 匹配） =====
    public static final String TASK_EAT = MOD_ID + ":eat";
    public static final String TASK_DRINK = MOD_ID + ":drink";
    /** @deprecated Use {@link #TASK_EAT}; retained for downstream source compatibility. */
    @Deprecated public static final String TASK_BLACKOUT_EAT = TASK_EAT;
    /** @deprecated Use {@link #TASK_DRINK}; retained for downstream source compatibility. */
    @Deprecated public static final String TASK_BLACKOUT_DRINK = TASK_DRINK;

    @Override
    public void onInitialize() {
        LOGGER.info("哈比列车核心 (HabiTrain Core) {} 初始化中...",
                net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID)
                        .orElseThrow().getMetadata().getVersion().getFriendlyString());
        // 1. 配置系统
        ConfigManager.getInstance().load();
        // Core is the single authoritative implementation of eat/drink in every SRE mode.
        com.habitrain.core.game.sre.CoreConsumableTasks.register();
        // Mod 菜单访问门控（独立文件 config/habitrain_menu_gate.json，服务端权威）
        com.habitrain.core.config.MenuGateService.load();
        // 角色扩展 v2 配置（独立版本化文件 config/habitrain_role_v2.json，服务端权威）
        com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE.load();
        com.habitrain.core.game.sre.KnifeDurabilityToggleService.register();
        // 2. 注册内置 GameMode（SRE 模式）
        //    构造 SRE 模式时会通过 SREGameModeBase 的静态初始化注册原版任务
        GameModeRegistry.register(MOD_ID, "sre:murder", new SREMurderMode());
        GameModeRegistry.register(MOD_ID, "sre:repair", new SRERepairMode());
        // 扫描 SRE 原版模式，注册轻量代理进 GameModeRegistry（须在 SERVER_STARTED freeze 前）。
        // 这样 SRE/Wathe 新版本新增的模式会自动出现在注册表、/habi_api list 与模式投票中。
        SREOriginalModeBridge.registerAll();
        // 投稿职业注册进 TMMRoles（须在对局开始前）
        // 角色扩展平台（v2 ADD）必须先于 HabiRoles.init()：迁移角色 crime_scapegoat
        // 由 CoreRoleExtensionProvider 经 role_extensions entrypoint 注册，并回填
        // HabiRoles.CRIME_SCAPEGOAT，供 HabiRoleEvents.init() 读取。
        com.habitrain.core.role.extension.RoleExtensionRegistry.init();
        com.habitrain.core.internal.CoreBootstrap.run(() ->
                com.habitrain.core.api.role.v2.RoleExtensionApi.instance().loadProviders());
        // MODIFY relation patches are linked only when a compiled lobby/round
        // snapshot is activated. Resolve keys through the catalog at that
        // materialization point, never while the pending snapshot is compiled.
        com.habitrain.core.role.extension.RoleRuntimeOverlayApplier.setRelationResolver(
                com.habitrain.core.role.change.RoleChangeServiceImpl::resolveViaCatalog);
        // 中央受管事件 dispatcher：注册全局监听器并绑定快照来源。
        com.habitrain.core.role.behavior.RoleHookRegistry.init();
        com.habitrain.core.role.behavior.RoleEventDispatcher.INSTANCE
                .setSnapshotProvider(com.habitrain.core.api.role.v2.RoleCatalogApi.instance()::snapshot);
        com.habitrain.core.role.behavior.RoleEventDispatcher.INSTANCE
                .setHookGates(com.habitrain.core.role.behavior.RuntimeHookGates.INSTANCE);
        com.habitrain.core.role.behavior.RoleEventDispatcher.INSTANCE.registerGlobalListeners();
        com.habitrain.core.role.capability.RoleChatCapabilityHooks.init();
        // 角色状态 v2：绑定 CCA 持久 store + 按 SyncPolicy 推送（Phase E，fix-doc §10）。
        {
            com.habitrain.core.role.state.RoleStateServiceImpl stateSvc =
                    (com.habitrain.core.role.state.RoleStateServiceImpl)
                            com.habitrain.core.api.role.v2.state.RoleStateApi.instance();
            stateSvc.setStore(new com.habitrain.core.role.state.CcaRoleStateStore());
            stateSvc.syncService().setRecipients(new com.habitrain.core.role.state.RoleStateSyncService.RecipientProvider() {
                @Override
                public java.util.Collection<java.util.UUID> allOnline() {
                    net.minecraft.server.MinecraftServer server =
                            com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE.server();
                    if (server == null) {
                        return java.util.List.of();
                    }
                    java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
                    for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                        ids.add(p.getUUID());
                    }
                    return ids;
                }

                @Override
                public java.util.Collection<java.util.UUID> inWorld(String worldKey) {
                    net.minecraft.server.MinecraftServer server =
                            com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE.server();
                    if (server == null || worldKey == null) {
                        return java.util.List.of();
                    }
                    java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
                    for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                        if (p.level() != null && p.level().dimension() != null
                                && worldKey.equals(p.level().dimension().location().toString())) {
                            ids.add(p.getUUID());
                        }
                    }
                    return ids;
                }

                @Override
                public java.util.Collection<java.util.UUID> trackersOf(java.util.UUID playerId) {
                    net.minecraft.server.MinecraftServer server =
                            com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE.server();
                    if (server == null || playerId == null) {
                        return java.util.List.of();
                    }
                    net.minecraft.server.level.ServerPlayer target =
                            server.getPlayerList().getPlayer(playerId);
                    if (target == null) {
                        return java.util.List.of();
                    }
                    java.util.LinkedHashSet<java.util.UUID> ids = new java.util.LinkedHashSet<>();
                    // Actual server-side entity trackers (players receiving the
                    // target entity's tracking packets).
                    for (net.minecraft.server.level.ServerPlayer observer
                            : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(target)) {
                        ids.add(observer.getUUID());
                    }
                    for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                        // Spectator camera followers may not be present in the
                        // ordinary tracking set, so retain that compatibility path.
                        net.minecraft.world.entity.Entity camera = p.getCamera();
                        if (camera != null && playerId.equals(camera.getUUID())) {
                            ids.add(p.getUUID());
                        }
                    }
                    return ids;
                }
            });
            stateSvc.syncService().setSender((playerId, payload) -> {
                net.minecraft.server.MinecraftServer server =
                        com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE.server();
                if (server == null) {
                    return;
                }
                net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                if (p != null) {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, payload);
                }
            });
        }
        // 角色变更服务：绑定 canonical 角色解析器到目录。
        ((com.habitrain.core.role.change.RoleChangeServiceImpl)
                com.habitrain.core.api.role.v2.RoleChangeApi.instance())
                .setResolver(com.habitrain.core.role.change.RoleChangeServiceImpl::resolveViaCatalog);
        ((com.habitrain.core.role.action.RoleActionServiceImpl)
                com.habitrain.core.api.role.v2.action.RoleActionApi.instance())
                .setS2cSender((player, id, payload) -> net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
                        .send(player, new com.habitrain.core.network.RoleActionS2CPayload(id, 0, true,
                                com.habitrain.core.api.role.v2.action.RoleActionResult.OK, payload, true)));
        ((com.habitrain.core.role.action.RoleActionServiceImpl)
                com.habitrain.core.api.role.v2.action.RoleActionApi.instance())
                .setResultSender((player, id, sequence, ok, reason, payload) ->
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                                new com.habitrain.core.network.RoleActionS2CPayload(id, sequence, ok, reason,
                                        payload, false)));
        // 角色动作握手门控（audit P1-4）：服务端权威决定该玩家能否执行角色动作；
        // 未完成 §14.2 握手（缺 provider / API 不兼容 / 定义 hash 不一致）一律拒绝。
        ((com.habitrain.core.role.action.RoleActionServiceImpl)
                com.habitrain.core.api.role.v2.action.RoleActionApi.instance())
                .setHandshakeGate(player -> player == null ? null
                        : com.habitrain.core.role.config.RoleHandshakeGate.INSTANCE
                                .blockReason(player.getUUID()));
        com.habitrain.core.game.sre.role.HabiRoles.init();
        // 角色覆盖注册系统初始化
        com.habitrain.core.role.override.RoleOverrideRegistry.init();
        com.habitrain.core.role.override.RoleOverrideLifecycleHandler.init();
        // 七美德修饰符（须在 HabiRoles 之后；慷慨只关联上游，不重复注册）
        com.habitrain.core.game.sre.modifier.HabiModifiers.init();
        // 装配 SRE 游戏状态提供者到 TaskManager（解除对 SRE 具体类的编译依赖）
        TaskManager.getInstance().setGameStateProvider(SREGameStateProvider.INSTANCE);
        // 按角色能力填充警长/杀手商店目录（canUseKiller=杀手商店, isVigilanteTeam=警长商店）
        // 3. 网络包类型注册
        NetworkRegistrar.init();
        // 4. 命令注册（/instantgroup, /habi_api）
        CommandRegistrar.init();
        // 5. 生命周期事件注册（SERVER_STARTED/STOPPING/JOIN/DISCONNECT）
        LifecycleEventsRegistrar.init();
        com.habitrain.core.game.sre.MatchEventBridge.register();
        // 5b. 环境控制器（对局开始/结束应用 lobby/match/post-match 天气与时间）
        EnvironmentController.registerEvents();
        MvpScoreTracker.init();
        // 6. C2S 接收器注册
        C2SReceiverRegistrar.init();
        EliminatedRestAreaService.init();
        // 7. 注册集中式缓慢重施管理器
        SlownessReapplyManager.registerTickHandler();
        // 8. 注册内置任务
        BuiltinTaskRegistrar.register();
        ModTickHandler.register();
        com.habitrain.core.scene.server.SceneRuntimeCoordinator.getInstance().init();
        com.habitrain.core.scene.compat.builtin.BuiltinSceneAdapters.registerCommon();
        com.habitrain.core.scene.item.HabiAdminItems.init();
        registerMoreSounds();
        initBetelSystem();
        LOGGER.info("哈比列车核心 初始化完成！已注册 {} 个 GameMode, {} 个任务",
                GameModeRegistry.size(), TaskRegistry.size());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private void registerMoreSounds() {
        Registry.register(BuiltInRegistries.SOUND_EVENT, BETEL_NUT_EAT_ID, BETEL_NUT_EAT_SOUND);
        Registry.register(BuiltInRegistries.SOUND_EVENT, BETEL_NUT_GET_ID, BETEL_NUT_GET_SOUND);
        Registry.register(BuiltInRegistries.SOUND_EVENT, LOOK_MY_EYES_ID, LOOK_MY_EYES_SOUND);
        Registry.register(BuiltInRegistries.SOUND_EVENT, BACKPACK_SEARCH_ID, BACKPACK_SEARCH_SOUND);
        Registry.register(BuiltInRegistries.SOUND_EVENT, MIKE_CODE_EDIT_ID, MIKE_CODE_EDIT_SOUND);
        LOGGER.info("已注册自定义音效: betel_nut_eat, betel_nut_get, look_my_eyes, backpack_search, mike_code_edit");
    }

    private void initBetelSystem() {
        BetelQuestState.init();
        BackpackQuestState.init();
        BetelQuestDefinition.register();
        BetelLeafHandler.register();
        BackpackSearchHandler.register();
        BetelFoodRestriction.register();
    }
}
