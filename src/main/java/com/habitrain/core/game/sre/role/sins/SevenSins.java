package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public final class SevenSins {
    private SevenSins() {}

    public static final ResourceLocation PRIDE_ID = HabiTrainCore.id("sin_pride");
    public static final ResourceLocation ENVY_ID = HabiTrainCore.id("sin_envy");
    public static final ResourceLocation WRATH_ID = HabiTrainCore.id("sin_wrath");
    public static final ResourceLocation GREED_ID = HabiTrainCore.id("sin_greed");
    public static final ResourceLocation GLUTTONY_ID = HabiTrainCore.id("sin_gluttony");
    public static final ResourceLocation LUST_ID = HabiTrainCore.id("sin_lust");
    public static final ResourceLocation SLOTH_ID = HabiTrainCore.id("sin_sloth");

    public static SRERole PRIDE, ENVY, WRATH, GREED, GLUTTONY, LUST, SLOTH;

    public static Set<ResourceLocation> allIds() {
        return Set.of(PRIDE_ID, ENVY_ID, WRATH_ID, GREED_ID, GLUTTONY_ID, LUST_ID, SLOTH_ID);
    }

    public static boolean isSin(SRERole role) {
        return role != null && allIds().contains(role.getIdentifier());
    }

    public static boolean isIndependentSin(SRERole role) {
        if (role == null) return false;
        ResourceLocation id = role.getIdentifier();
        return PRIDE_ID.equals(id) || GREED_ID.equals(id)
                || LUST_ID.equals(id) || SLOTH_ID.equals(id);
    }

    public static boolean isKillerShareSin(SRERole role) {
        return role != null && WRATH_ID.equals(role.getIdentifier());
    }

    public static void init() {
        // 七宗罪角色已由 CoreRoleExtensionProvider 经 v2 ADD + RoleFactory 注册并回填字段。
        // 技能由 RoleDefinition.skill 托管；角色行为（战斗/交互/生命周期/互斥）
        // 已迁移到 SevenSinV2BehaviorHooks 的 v2 托管钩子。
        wireOpposingClique();
        com.habitrain.core.game.sre.role.sins.win.SinVictoryHooks.init();
        // v2 win hooks moved into CoreRoleExtensionProvider.registerWith
        // (audit P1-1): no process-global registrar writes.
        SevenSinEvents.init();
        HabiTrainCore.LOGGER.info(
                "[SevenSins] registered 7 sins: pride, envy, wrath, greed, gluttony, lust, sloth");
    }

    private static void wireOpposingClique() {
        SRERole[] sins = {PRIDE, ENVY, WRATH, GREED, GLUTTONY, LUST, SLOTH};
        for (int i = 0; i < sins.length; i++) {
            // v2 回填的字段在角色被禁用/注册失败时为 null；这里跳过空角色，
            // 避免 init 阶段 NPE 中断后续 SinVictoryHooks/SevenSinEvents 的注册。
            if (sins[i] == null) continue;
            for (int j = i + 1; j < sins.length; j++) {
                if (sins[j] == null) continue;
                sins[i].addTwoWayOpposingRole(sins[j]);
            }
        }
    }
}
