package com.habitrain.core.role.extension;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.role.v2.RoleExtensionEntrypoint;
import com.habitrain.core.api.role.v2.RoleExtensionRegistrar;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleEconomyProfile;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RoleInventoryProfile;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.HabiRoleShops;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.component.CrimeScapegoatComponent;
import com.habitrain.core.game.sre.role.component.FlowerGirlComponent;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.component.SwiftWindComponent;
import com.habitrain.core.game.sre.role.skill.MikeCodeEditSkill;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;

import java.awt.Color;

/**
 * Core's own v2 {@code role_extensions} provider.
 *
 * <p>Registers habitrain_core's five pack roles through the v2 {@code ADD}
 * model. Compiled instances are assigned back to {@link HabiRoles} fields so
 * existing event / CCA / shop wiring keeps working.
 */
public final class CoreRoleExtensionProvider implements RoleExtensionEntrypoint {

    @Override
    public void register(RoleExtensionRegistrar registrar) {
        HabiRoles.CRIME_SCAPEGOAT = registrar.add(RoleDefinition.builder(HabiRoles.CRIME_SCAPEGOAT_ID)
                .presentation(RolePresentation.builder()
                        .color(new Color(120, 90, 60).getRGB())
                        .build())
                .faction(RoleFactionProfile.builder()
                        .innocent()
                        .build())
                .spawn(RoleSpawnProfile.builder()
                        .defaultMax(1)
                        .needPlayerCount(10)
                        .build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .componentKey(CrimeScapegoatComponent.KEY)
                        .canSeeCoin()
                        .canPickUpRevolver()
                        .build())
                .maxSprintTime(TMMRoles.CIVILIAN.getMaxSprintTime())
                .build());

        HabiRoles.FLOWER_GIRL = registrar.add(RoleDefinition.builder(HabiRoles.FLOWER_GIRL_ID)
                .presentation(RolePresentation.builder()
                        .color(new Color(255, 105, 180).getRGB())
                        .build())
                .faction(RoleFactionProfile.builder()
                        .innocent()
                        .build())
                .spawn(RoleSpawnProfile.builder().defaultMax(1).build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .componentKey(FlowerGirlComponent.KEY)
                        .canSeeCoin()
                        .build())
                .inventory(RoleInventoryProfile.builder()
                        .item(HabiRoleItems.createBouquet(1))
                        .build())
                .economy(RoleEconomyProfile.builder()
                        .live(HabiRoleShops::flowerGirlShop)
                        .build())
                .skill(RoleSkillSpec.of(RoleSkill.skill(
                        HabiTrainCore.id("flower_girl_gift"),
                        "skill.habitrain_core.flower_girl.gift",
                        FlowerGirlComponent::useGift
                ).cooldownSeconds(30).showOnHud(true).announceToSelf(true).build()))
                .maxSprintTime(TMMRoles.CIVILIAN.getMaxSprintTime())
                .build());

        HabiRoles.SWIFT_WIND = registrar.add(RoleDefinition.builder(HabiRoles.SWIFT_WIND_ID)
                .presentation(RolePresentation.builder()
                        .color(new Color(70, 200, 230).getRGB())
                        .moodType(SRERole.MoodType.FAKE)
                        .build())
                .faction(RoleFactionProfile.builder()
                        .killer()
                        .build())
                .spawn(RoleSpawnProfile.builder().defaultMax(1).build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .componentKey(SwiftWindComponent.KEY)
                        .canSeeCoin()
                        .build())
                .economy(RoleEconomyProfile.builder()
                        .live(HabiRoleShops::swiftWindShop)
                        .build())
                .skill(RoleSkillSpec.of(RoleSkill.skill(
                        HabiTrainCore.id("swift_wind_dash"),
                        "skill.habitrain_core.swift_wind.dash",
                        SwiftWindComponent::useDash
                ).cooldownSeconds(20).showOnHud(true).announceToSelf(true).build()))
                .skill(RoleSkillSpec.of(RoleSkill.skill(
                        HabiTrainCore.id("swift_wind_smoke"),
                        "skill.habitrain_core.swift_wind.smoke",
                        SwiftWindComponent::useSmoke
                ).cooldownSeconds(120).shifted(true).showOnHud(true).announceToSelf(true).build()))
                .maxSprintTime(Integer.MAX_VALUE)
                .canSeeTime(true)
                .build());

        HabiRoles.MIME_KILLER = registrar.add(RoleDefinition.builder(HabiRoles.MIME_KILLER_ID)
                .presentation(RolePresentation.builder()
                        .color(new Color(40, 40, 40).getRGB())
                        .moodType(SRERole.MoodType.FAKE)
                        .build())
                .faction(RoleFactionProfile.builder()
                        .killer()
                        .build())
                .spawn(RoleSpawnProfile.builder()
                        .defaultMax(1)
                        .needPlayerCount(12)
                        .build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .componentKey(MimeKillerComponent.KEY)
                        .canSeeCoin()
                        .build())
                .economy(RoleEconomyProfile.builder()
                        .live(HabiRoleShops::mimeKillerShop)
                        .build())
                .skill(RoleSkillSpec.of(RoleSkill.skill(
                        HabiTrainCore.id("mime_killer_mime"),
                        "skill.habitrain_core.mime_killer.mime",
                        MimeKillerComponent::useMime
                ).cooldownSeconds(30).showOnHud(true).announceToSelf(true).build()))
                .maxSprintTime(Integer.MAX_VALUE)
                .canSeeTime(true)
                .build());

        HabiRoles.MIKE = registrar.add(RoleDefinition.builder(HabiRoles.MIKE_ID)
                .presentation(RolePresentation.builder()
                        .color(new Color(78, 201, 176).getRGB())
                        .build())
                .faction(RoleFactionProfile.builder()
                        .innocent()
                        .build())
                .spawn(RoleSpawnProfile.builder().defaultMax(1).build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .canSeeCoin()
                        .build())
                .skill(RoleSkillSpec.of(RoleSkill.skill(
                        HabiTrainCore.id("mike_code_edit"),
                        "skill.habitrain_core.mike.code_edit",
                        MikeCodeEditSkill::use
                ).cooldownSeconds(120).showOnHud(true).announceToSelf(true).build()))
                .maxSprintTime(TMMRoles.CIVILIAN.getMaxSprintTime())
                .build());

        // Managed behavior hooks for core's own roles (audit P1-1): they must
        // register through this provider-scoped registrar, not a process-global
        // write path, so identity/rollback/config gating stay uniform.
        com.habitrain.core.game.sre.role.HabiRoleHooks.registerWith(registrar);
        com.habitrain.core.game.sre.role.sins.win.SevenSinV2Hooks.registerWith(registrar);
    }
}