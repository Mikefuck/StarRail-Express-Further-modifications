package com.habitrain.core.game.blackout;

import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Blackout mode specific roles.
 *
 * These roles are registered separately from the default SRE murder roles so
 * Blackout can tweak visibility rules without affecting other game modes.
 */
public final class BlackoutRoles {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutRoles");

    public static final ResourceLocation CIVILIAN_ID = SRE.id("blackout_civilian");
    public static final ResourceLocation KILLER_ID = SRE.id("blackout_killer");
    public static final ResourceLocation SHERIFF_ID = SRE.id("blackout_sheriff");
    public static final ResourceLocation ENGINEER_TEMPLATE_ID = SRE.id("blackout_engineer_template");

    /**
     * 警长投票时被票选者可随机到的警察职业池。
     * 仅在 {@link #registerExtraRoles()} 解析成功后填充，元素为 SRE 原版角色 id。
     */
    public static final Set<ResourceLocation> POLICE_ROLE_IDS = new java.util.HashSet<>();

    public static final BlackoutRoleDefinition CIVILIAN = role(
            CIVILIAN_ID,
            "平民",
            "好人阵营\n普通乘客。\n\n• 完成好人任务\n• 存活到最后\n• 帮助警长找出杀手",
            "平民",
            "好人阵营。完成任务并活到最后。",
            "完成好人任务，存活到对局结束，并帮助好人阵营获胜。",
            BlackoutRoleManager.Faction.GOOD,
            0x55FF55,
            true,
            false,
            false,
            SRERole.MoodType.REAL,
            200,
            false,
            false
    );

    public static final BlackoutRoleDefinition KILLER = role(
            KILLER_ID,
            "杀手",
            "坏人阵营\n混入人群中的破坏者。\n\n• 消灭所有好人\n• 破坏列车供电\n• 不要暴露身份",
            "杀手",
            "坏人阵营。利用黑暗清除所有好人。",
            "消灭所有好人并破坏列车供电。",
            BlackoutRoleManager.Faction.BAD,
            0xFF5555,
            false,
            true,
            false,
            SRERole.MoodType.FAKE,
            200,
            false,
            true
    );

    public static final BlackoutRoleDefinition SHERIFF = role(
            SHERIFF_ID,
            "警长",
            "好人阵营\n维护正义的执法者。\n\n• 商店仅保留手铐和左轮手枪\n• 使用配枪制裁杀手并保护好自己",
            "警长",
            "好人阵营。找出杀手并维持秩序。",
            "找出并处决杀手，保护平民直到胜利。",
            BlackoutRoleManager.Faction.GOOD,
            0xFFFF55,
            true,
            true,
            true,
            SRERole.MoodType.REAL,
            200,
            false,
            false
    );

    /**
     * 代码内预留的角色模板，不会注册到游戏中。
     *
     * 用途：后续如果要做“工程师/维修员/侦查员”等新角色，可以直接复用这里的命名、说明和构建方式，
     * 不影响当前游戏内可选角色池。
     */
    public static final BlackoutRoleDefinition ENGINEER_TEMPLATE = role(
            ENGINEER_TEMPLATE_ID,
            "工程师（模板）",
            "代码预留模板，不会在当前版本中注册。\n\n• 可用于后续新增角色的开发参考\n• 目前不参与随机分配\n• 目前不进入角色注册表",
            "工程师（模板）",
            "代码预留角色模板，当前版本不可用。",
            "开发模板：当前版本不启用。",
            BlackoutRoleManager.Faction.GOOD,
            0x55AADD,
            true,
            false,
            false,
            SRERole.MoodType.REAL,
            200,
            false,
            false
    );

    private static final List<BlackoutRoleDefinition> DEFAULT_ROLES = List.of(CIVILIAN, KILLER, SHERIFF);
    private static final List<BlackoutRoleDefinition> DEVELOPMENT_TEMPLATES = List.of(ENGINEER_TEMPLATE);

    /**
     * 原版 SRE 角色 id，停电模式复用这些 SRERole 实例。
     * namespace 为 {@code noellesroles}（SRE/noellesroles 模组）。
     */
    private static final ResourceLocation GUARD_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "guard");
    private static final ResourceLocation PATROLLER_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "patroller");
    private static final ResourceLocation SWAST_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "swast");
    private static final ResourceLocation ELF_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "elf");
    private static final ResourceLocation EXECUTIONER_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "executioner");
    private static final ResourceLocation SILENCER_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "silencer");
    private static final ResourceLocation CLEANER_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "cleaner");
    // 平民池角色（初始随机分配的 7 个好人职业，替代原平民）
    private static final ResourceLocation BUILDER_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "builder");
    private static final ResourceLocation MONITOR_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "monitor");
    private static final ResourceLocation MAGICIAN_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "magician");
    private static final ResourceLocation PHOTOGRAPHER_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "photographer");
    private static final ResourceLocation FIREFIGHTER_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "firefighter");
    private static final ResourceLocation COURIER_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "courier");
    private static final ResourceLocation TELEGRAPHER_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "telegrapher");

    private static boolean registered = false;

    private BlackoutRoles() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        for (BlackoutRoleDefinition definition : DEFAULT_ROLES) {
            BlackoutRoleRegistry.register(definition);
            TMMRoles.registerRole(definition.sreRole());
            LOGGER.info("[BlackoutRegister] default role registered: id={} displayName={} selectable={}",
                    definition.identifier(), definition.displayName(), definition.selectableInRandomAssignment());
        }

        registerExtraRoles();
        LOGGER.info("[BlackoutRegister] final registry size: {} roles", BlackoutRoleRegistry.getAll().size());
        for (BlackoutRoleDefinition d : BlackoutRoleRegistry.getAll()) {
            LOGGER.info("[BlackoutRegister] registry entry: id={} displayName={} faction={} selectable={}",
                    d.identifier(), d.displayName(), d.faction(), d.selectableInRandomAssignment());
        }
    }

    /**
     * 复用 SRE 原版角色（guard/patroller/swast/elf/executioner/silencer/cleaner），
     * 包装成 {@link BlackoutRoleDefinition} 注册到 {@link BlackoutRoleRegistry}。
     * <p>
     * 这些角色的 SRERole 实例由 SRE/noellesroles 在其初始化阶段注册到 {@link TMMRoles#ROLES}，
     * 这里仅做查找引用，不重复调用 {@link TMMRoles#registerRole}（重复注册会抛异常）。
     * <p>
     * 必须在 SRE 模组初始化完成后调用（即本模组 onInitialize 之后），否则 ModRoles 字段尚未填充。
     * 若某个 SRE 角色在新版本中被改名/移除，这里会记录警告并跳过，不影响其余角色和 3 个默认角色。
     */
    private static void registerExtraRoles() {
        POLICE_ROLE_IDS.clear();

        registerExtra(GUARD_ID, "保安",
                "好人阵营\n列车安保人员。\n\n• 守护乘客安全\n• 协助警长维持秩序",
                "保安", "好人阵营。守护乘客安全。",
                "守护乘客，协助好人阵营获胜。",
                BlackoutRoleManager.Faction.GOOD, false);
        registerExtra(PATROLLER_ID, "巡警",
                "好人阵营\n巡逻执法者。\n\n• 巡查车厢\n• 制裁可疑人员",
                "巡警", "好人阵营。巡查车厢。",
                "巡查车厢，制裁可疑人员，协助好人获胜。",
                BlackoutRoleManager.Faction.GOOD, false);
        registerExtra(SWAST_ID, "特警",
                "好人阵营\n精锐狙击手。\n\n• 精准火力\n• 制裁杀手",
                "特警", "好人阵营。精准火力。",
                "用精准火力制裁杀手，保护好人阵营。",
                BlackoutRoleManager.Faction.GOOD, false);
        registerExtra(ELF_ID, "游侠",
                "好人阵营\n敏捷的探索者。\n\n• 完成好人任务\n• 存活到最后",
                "游侠", "好人阵营。敏捷探索者。",
                "完成好人任务，存活到最后，协助好人获胜。",
                BlackoutRoleManager.Faction.GOOD, false);
        registerExtra(EXECUTIONER_ID, "刽子手",
                "坏人阵营\n精准猎杀者。\n\n• 消灭所有好人\n• 破坏列车供电\n• 不要暴露身份",
                "刽子手", "坏人阵营。精准猎杀者。",
                "消灭所有好人并破坏列车供电。",
                BlackoutRoleManager.Faction.BAD, true);
        registerExtra(SILENCER_ID, "静语者",
                "坏人阵营\n沉默的清除者。\n\n• 消灭所有好人\n• 破坏列车供电\n• 不要暴露身份",
                "静语者", "坏人阵营。沉默的清除者。",
                "消灭所有好人并破坏列车供电。",
                BlackoutRoleManager.Faction.BAD, true);
        registerExtra(CLEANER_ID, "清道夫",
                "坏人阵营\n善后的清理者。\n\n• 消灭所有好人\n• 破坏列车供电\n• 不要暴露身份",
                "清道夫", "坏人阵营。善后的清理者。",
                "消灭所有好人并破坏列车供电。",
                BlackoutRoleManager.Faction.BAD, true);

        // 平民池 7 个好人职业（替代原平民角色，初始随机分配时从中抽取）
        registerExtra(BUILDER_ID, "建筑师",
                "好人阵营\n用墙体隔绝风险。\n\n• 完成好人任务\n• 用墙体保护乘客\n• 存活到最后",
                "建筑师", "好人阵营。用墙体隔绝风险。",
                "建造墙体保护乘客，完成好人任务并存活到最后。",
                BlackoutRoleManager.Faction.GOOD, true);
        registerExtra(MONITOR_ID, "监察员",
                "好人阵营\n监控位置的监察者。\n\n• 完成好人任务\n• 监视可疑人员\n• 存活到最后",
                "监察员", "好人阵营。监控位置的监察者。",
                "监视可疑人员，完成好人任务并存活到最后。",
                BlackoutRoleManager.Faction.GOOD, true);
        registerExtra(MAGICIAN_ID, "魔术师",
                "好人阵营\n用魔术技巧迷惑杀手。\n\n• 完成好人任务\n• 扮演其他角色\n• 存活到最后",
                "魔术师", "好人阵营。用魔术技巧迷惑杀手。",
                "用魔术技巧迷惑杀手，保护乘客并存活到最后。",
                BlackoutRoleManager.Faction.GOOD, true);
        registerExtra(PHOTOGRAPHER_ID, "摄影师",
                "好人阵营\n拍下罪行发现杀手。\n\n• 完成好人任务\n• 拍下可疑行为\n• 存活到最后",
                "摄影师", "好人阵营。拍下罪行发现杀手。",
                "拍下所有罪行，发现潜入人群中的杀手并存活到最后。",
                BlackoutRoleManager.Faction.GOOD, true);
        registerExtra(FIREFIGHTER_ID, "消防员",
                "好人阵营\n应对紧急情况。\n\n• 完成好人任务\n• 使用消防斧和灭火器\n• 存活到最后",
                "消防员", "好人阵营。应对紧急情况。",
                "使用消防斧和灭火器应对紧急情况，完成好人任务并存活到最后。",
                BlackoutRoleManager.Faction.GOOD, true);
        registerExtra(COURIER_ID, "信使",
                "好人阵营\n传递希望与物资。\n\n• 完成好人任务\n• 用信件传递物资\n• 存活到最后",
                "信使", "好人阵营。传递希望与物资。",
                "用信件为乘客传递希望与物资，完成好人任务并存活到最后。",
                BlackoutRoleManager.Faction.GOOD, true);
        registerExtra(TELEGRAPHER_ID, "电报员",
                "好人阵营\n发送匿名电报。\n\n• 完成好人任务\n• 用电报传递信息\n• 存活到最后",
                "电报员", "好人阵营。发送匿名电报。",
                "向所有玩家发送匿名电报传递信息，完成好人任务并存活到最后。",
                BlackoutRoleManager.Faction.GOOD, true);
    }

    /**
     * 解析一个 SRE 原版角色并包装注册到停电模式。
     *
     * @param sreId                  SRE 原版角色 ResourceLocation
     * @param displayName            中文显示名
     * @param description            角色介绍面板描述
     * @param announcementName       开局报幕角色名
     * @param announcementSubtitle   开局报幕副标题
     * @param announcementGoal       开局报幕目标
     * @param faction                停电模式阵营
     * @param selectableInRandomAssignment 是否参与随机分配
     */
    private static void registerExtra(ResourceLocation sreId, String displayName, String description,
                                       String announcementName, String announcementSubtitle, String announcementGoal,
                                       BlackoutRoleManager.Faction faction, boolean selectableInRandomAssignment) {
        SRERole sreRole = TMMRoles.getRole(sreId);
        if (sreRole == null) {
            LOGGER.warn("[BlackoutRegister] SRE 原版角色 {} 未注册，跳过停电模式复用。可能 SRE 版本变更或加载顺序问题。", sreId);
            return;
        }

        BlackoutRoleDefinition definition = new BlackoutRoleDefinition(
                sreId,
                displayName,
                description,
                announcementName,
                announcementSubtitle,
                announcementGoal,
                faction,
                sreRole,
                selectableInRandomAssignment
        );

        try {
            BlackoutRoleRegistry.register(definition);
        } catch (IllegalArgumentException already) {
            LOGGER.warn("停电角色 {} 已注册，跳过：{}", sreId, already.getMessage());
            return;
        }

        if (faction == BlackoutRoleManager.Faction.GOOD
                && (sreId.equals(GUARD_ID) || sreId.equals(PATROLLER_ID)
                        || sreId.equals(SWAST_ID) || sreId.equals(ELF_ID))) {
            POLICE_ROLE_IDS.add(sreId);
        }
        LOGGER.debug("停电模式已复用 SRE 角色: {} -> {}", sreId, displayName);
    }

    public static BlackoutRoleDefinition getCivilianDefinition() {
        return CIVILIAN;
    }

    public static BlackoutRoleDefinition getKillerDefinition() {
        return KILLER;
    }

    public static BlackoutRoleDefinition getSheriffDefinition() {
        return SHERIFF;
    }

    public static List<BlackoutRoleDefinition> getDevelopmentTemplates() {
        return DEVELOPMENT_TEMPLATES;
    }

    private static BlackoutRoleDefinition role(ResourceLocation id, String displayName, String description,
                                               String announcementName, String announcementSubtitle, String announcementGoal,
                                               BlackoutRoleManager.Faction faction, int color, boolean isInnocent,
                                               boolean canUseKiller, boolean vigilanteTeam, SRERole.MoodType moodType, int maxSprintTime,
                                               boolean canSeeTime, boolean selectableInRandomAssignment) {
        SRERole role = new NormalRole(id, color, isInnocent, canUseKiller, moodType, maxSprintTime, canSeeTime)
                .setCanBeRandomedByOtherRoles(false);
        if (canUseKiller) {
            role.setCanUseInstinct(true);
        }
        role.setVigilanteTeam(vigilanteTeam);
        return new BlackoutRoleDefinition(
                id,
                displayName,
                description,
                announcementName,
                announcementSubtitle,
                announcementGoal,
                faction,
                role,
                selectableInRandomAssignment
        );
    }
}
