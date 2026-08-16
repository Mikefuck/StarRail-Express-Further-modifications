package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.component.EnvyComponent;
import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import com.habitrain.core.game.sre.role.sins.component.GreedComponent;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.game.sre.role.sins.component.PrideComponent;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import com.habitrain.core.game.sre.role.sins.shop.SevenSinShops;
import io.wifi.starrailexpress.api.RoleSkill;
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
        // 角色行为（战斗/交互/生命周期/互斥）已迁移到 SevenSinV2BehaviorHooks 的 v2 托管钩子。
        registerSkills();
        wireOpposingClique();
        com.habitrain.core.game.sre.role.sins.win.SinVictoryHooks.init();
        // v2 win hooks moved into CoreRoleExtensionProvider.registerWith
        // (audit P1-1): no process-global registrar writes.
        SevenSinEvents.init();
        HabiTrainCore.LOGGER.info(
                "[SevenSins] registered 7 sins: pride, envy, wrath, greed, gluttony, lust, sloth");
    }

    private static void registerSkills() {
        if (ENVY != null) {
            RoleSkill.register(ENVY,
                    RoleSkill.skill(
                            EnvyComponent.MARK_SKILL_ID,
                            "skill.habitrain_core.sin_envy.mark",
                            EnvyComponent::useMark
                    ).cooldownSeconds(EnvyComponent.MARK_CD_SECONDS)
                            .showOnHud(true)
                            .announceToSelf(true)
                            .build()
            );
        }
        if (SLOTH != null) {
            // Once-per-game: component gates onceAwakeUsed; no cooldown needed.
            RoleSkill.register(SLOTH,
                    RoleSkill.skill(
                            HabiTrainCore.id("sin_sloth_awake"),
                            "skill.habitrain_core.sin_sloth.awake",
                            SlothComponent::useAwake
                    ).cooldownSeconds(1)
                            .showOnHud(true)
                            .announceToSelf(true)
                            .build()
            );
        }
        if (LUST != null) {
            // Phase 1: toggle observe (charge in component tick). Phase 2: once desire mark.
            RoleSkill.register(LUST,
                    RoleSkill.skill(
                            HabiTrainCore.id("sin_lust_observe"),
                            "skill.habitrain_core.sin_lust.observe",
                            LustComponent::useObserve
                    ).cooldownSeconds(1)
                            .showOnHud(true)
                            .announceToSelf(true)
                            .build(),
                    RoleSkill.skill(
                            HabiTrainCore.id("sin_lust_desire"),
                            "skill.habitrain_core.sin_lust.desire",
                            LustComponent::useDesireMark
                    ).cooldownSeconds(1)
                            .showOnHud(true)
                            .announceToSelf(true)
                            .build()
            );
        }
        if (GREED != null) {
            // Steal one random transferable item from crosshair target.
            RoleSkill.register(GREED,
                    RoleSkill.skill(
                            GreedComponent.STEAL_SKILL_ID,
                            "skill.habitrain_core.sin_greed.steal",
                            GreedComponent::useSteal
                    ).cooldownSeconds(GreedComponent.STEAL_CD_SECONDS)
                            .showOnHud(true)
                            .announceToSelf(true)
                            .build()
            );
        }
        if (GLUTTONY != null) {
            // Passive eat-buff CD shown on unified skill HUD (handler never activates).
            RoleSkill.register(GLUTTONY,
                    RoleSkill.skill(
                            GluttonyComponent.BUFF_SKILL_ID,
                            "skill.habitrain_core.sin_gluttony.buff",
                            GluttonyComponent::useBuffSkillHud
                    ).cooldownSeconds(GluttonyComponent.BUFF_CD_SECONDS)
                            .showOnHud(true)
                            .announceToSelf(false)
                            .build()
            );
        }
    }

    private static void wireOpposingClique() {
        SRERole[] sins = {PRIDE, ENVY, WRATH, GREED, GLUTTONY, LUST, SLOTH};
        for (int i = 0; i < sins.length; i++) {
            for (int j = i + 1; j < sins.length; j++) {
                sins[i].addTwoWayOpposingRole(sins[j]);
            }
        }
    }
}