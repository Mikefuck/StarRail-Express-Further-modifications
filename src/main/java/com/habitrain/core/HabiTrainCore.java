package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.betel.BetelFoodRestriction;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelQuestDefinition;
import com.habitrain.core.betel.BetelQuestState;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.blackout.BlackoutDeathHandler;
import com.habitrain.core.game.blackout.BlackoutHornVoteHandler;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutPhoneHandler;
import com.habitrain.core.game.blackout.sre.SREBlackoutGameLauncher;
import com.habitrain.core.game.blackout.sre.SREBlackoutGameMode;
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
import betel.nut.BetelNutConfig;
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
    public static final ResourceLocation PHONE_OPERATOR_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "phone_operator");
    public static final SoundEvent PHONE_OPERATOR_SOUND = SoundEvent.createVariableRangeEvent(PHONE_OPERATOR_ID);
    public static final ResourceLocation PHONE_RING_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "phone_ring");
    public static final SoundEvent PHONE_RING_SOUND = SoundEvent.createVariableRangeEvent(PHONE_RING_ID);
    public static final ResourceLocation MIKE_CODE_EDIT_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "mike_code_edit");
    public static final SoundEvent MIKE_CODE_EDIT_SOUND = SoundEvent.createVariableRangeEvent(MIKE_CODE_EDIT_ID);

    // ===== 任务 ID 常量（全限定字符串，供 mixin/追踪器按 getFullId() 匹配） =====
    public static final String TASK_BLACKOUT_EAT = MOD_ID + ":blackout_eat";
    public static final String TASK_BLACKOUT_DRINK = MOD_ID + ":blackout_drink";
    public static final String TASK_BLACKOUT_SEARCH_BACKPACK = MOD_ID + ":blackout_search_backpack";
    public static final String TASK_BLACKOUT_BETEL_QUEST = MOD_ID + ":blackout_betel_quest";
    public static final String TASK_BLACKOUT_PET_CAT = MOD_ID + ":blackout_pet_cat";
    public static final String TASK_BLACKOUT_BE_ALONE = MOD_ID + ":blackout_be_alone";
    public static final String TASK_BLACKOUT_LOOK_MY_EYES = MOD_ID + ":blackout_look_my_eyes";
    public static final String TASK_ADD_COAL = MOD_ID + ":add_coal";
    public static final String TASK_REPAIR_WIRING = MOD_ID + ":repair_wiring";
    public static final String TASK_MAINTAIN_POWER = MOD_ID + ":maintain_power";

    @Override
    public void onInitialize() {
        LOGGER.info("哈比列车核心 (HabiTrain Core) 初始化中...");
        // 1. 配置系统
        ConfigManager.getInstance().load();
        // Mod 菜单访问门控（独立文件 config/habitrain_menu_gate.json，服务端权威）
        com.habitrain.core.config.MenuGateService.load();
        com.habitrain.core.game.sre.KnifeDurabilityToggleService.register();
        // 2. 注册内置 GameMode（SRE 模式 + 停电模式）
        //    构造 SRE 模式时会通过 SREGameModeBase 的静态初始化注册原版任务
        GameModeRegistry.register(MOD_ID, "sre:murder", new SREMurderMode());
        GameModeRegistry.register(MOD_ID, "sre:repair", new SRERepairMode());
        BlackoutMode blackoutMode = new BlackoutMode();
        blackoutMode.setSreGameLauncher(SREBlackoutGameLauncher.INSTANCE);
        GameModeRegistry.register(MOD_ID, "habitrain:blackout", blackoutMode);
        // 注册停电模式专用的 SRE GameMode（复用 SRE 原版角色分配流程）。
        SREBlackoutGameMode.register();
        // 扫描 SRE 原版模式，注册轻量代理进 GameModeRegistry（须在 SERVER_STARTED freeze 前）。
        // 这样 SRE/Wathe 新版本新增的模式会自动出现在注册表、/habi_api list 与模式投票中。
        SREOriginalModeBridge.registerAll();
        // 投稿职业注册进 TMMRoles（须在对局开始前）
        com.habitrain.core.game.sre.role.HabiRoles.init();
        // 角色覆盖注册系统初始化
        com.habitrain.core.role.override.RoleOverrideRegistry.init();
        com.habitrain.core.role.override.RoleOverrideLifecycleHandler.init();
        // 七美德修饰符（须在 HabiRoles 之后；慷慨只关联上游，不重复注册）
        com.habitrain.core.game.sre.modifier.HabiModifiers.init();
        // 装配 SRE 游戏状态提供者到 TaskManager（解除对 SRE 具体类的编译依赖）
        TaskManager.getInstance().setGameStateProvider(SREGameStateProvider.INSTANCE);
        // 按角色能力填充警长/杀手商店目录（canUseKiller=杀手商店, isVigilanteTeam=警长商店）
        // 3. 网络包注册（16 个 payload type）
        NetworkRegistrar.init();
        // 4. 命令注册（/instantgroup, /habi_api）
        CommandRegistrar.init();
        // 5. 生命周期事件注册（SERVER_STARTED/STOPPING/JOIN/DISCONNECT）
        LifecycleEventsRegistrar.init();
        // 5b. 环境控制器（对局开始/结束应用 lobby/match/post-match 天气与时间）
        EnvironmentController.registerEvents();
        MvpScoreTracker.init();
        // 6. C2S 接收器注册（4 个客户端→服务端包处理器）
        C2SReceiverRegistrar.init();
        EliminatedRestAreaService.init();
        // 7. 注册集中式缓慢重施管理器
        SlownessReapplyManager.registerTickHandler();
        // 8. 注册内置任务
        BuiltinTaskRegistrar.register();
        ModTickHandler.register();
        // 停电模式任务注册
        com.habitrain.core.game.blackout.task.AddCoalTask.register();
        com.habitrain.core.game.blackout.task.AddCoalHandler.register();
        com.habitrain.core.game.blackout.task.RepairWiringTask.register();
        com.habitrain.core.game.blackout.task.RepairWiringHandler.register();
        com.habitrain.core.game.blackout.task.SabotageWiringTask.register();
        com.habitrain.core.game.blackout.task.SabotageWiringHandler.register();
        com.habitrain.core.game.blackout.task.FurnaceExplosionTask.register();
        com.habitrain.core.game.blackout.task.FurnaceExplosionHandler.register();
        com.habitrain.core.game.blackout.task.MaintainPowerTask.register();
        com.habitrain.core.game.blackout.task.MaintainPowerHandler.register();
        com.habitrain.core.game.blackout.task.RestorePowerTask.register();
        com.habitrain.core.game.blackout.task.RestorePowerHandler.register();

        // 停电模式日常任务（7个，加入 BLACKOUT_GOOD 池，也自动成为坏人假任务池）
        com.habitrain.core.game.blackout.task.BlackoutEatTask.register();
        com.habitrain.core.game.blackout.task.BlackoutDrinkTask.register();
        com.habitrain.core.game.blackout.task.BlackoutSearchBackpackTask.register();
        com.habitrain.core.game.blackout.task.BlackoutBetelQuestTask.register();
        com.habitrain.core.game.blackout.task.BlackoutPetCatTask.register();
        com.habitrain.core.game.blackout.task.BlackoutBeAloneTask.register();
        com.habitrain.core.game.blackout.task.BlackoutLookMyEyesTask.register();
        BlackoutPhoneHandler.register();
        BlackoutDeathHandler.register();
        BlackoutHornVoteHandler.register();
        com.habitrain.core.game.blackout.shop.BlackoutTaskShopHandler.register();
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
        Registry.register(BuiltInRegistries.SOUND_EVENT, PHONE_OPERATOR_ID, PHONE_OPERATOR_SOUND);
        Registry.register(BuiltInRegistries.SOUND_EVENT, PHONE_RING_ID, PHONE_RING_SOUND);
        Registry.register(BuiltInRegistries.SOUND_EVENT, MIKE_CODE_EDIT_ID, MIKE_CODE_EDIT_SOUND);
        LOGGER.info("已注册自定义音效: betel_nut_eat, betel_nut_get, look_my_eyes, backpack_search, phone_operator, phone_ring, mike_code_edit");
    }

    private void initBetelSystem() {
        var betelConfig = BetelNutConfig.get();
        if (!betelConfig.enableAddictionSystem) {
            betelConfig.enableAddictionSystem = true;
            LOGGER.info("已强制开启槟榔mod的成瘾系统（覆盖配置文件设置）");
        } else {
            LOGGER.info("槟榔mod的成瘾系统已开启");
        }
        BetelQuestState.init();
        BackpackQuestState.init();
        BetelQuestDefinition.register();
        BetelLeafHandler.register();
        BackpackSearchHandler.register();
        BetelFoodRestriction.register();
    }
}
