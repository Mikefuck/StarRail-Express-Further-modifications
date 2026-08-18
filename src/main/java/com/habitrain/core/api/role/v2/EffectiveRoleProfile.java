package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.patch.DefaultItemsPatch;
import com.habitrain.core.api.role.patch.FlagsPatch;
import com.habitrain.core.api.role.patch.NamePatch;
import com.habitrain.core.api.role.patch.RoleTextPatch;
import com.habitrain.core.api.role.patch.ShopPatch;
import com.habitrain.core.api.role.patch.ShopTransform;
import com.habitrain.core.api.role.patch.SpawnInfoPatch;
import com.habitrain.core.api.role.patch.WinConditionHook;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.SRERole.MoodType;
import io.wifi.starrailexpress.api.SRERole.SpecialMapRoleMap;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, serializable-in-principle description of an effective role.
 *
 * <p>Snapshots and their archive retain this value object rather than a live
 * {@link SRERole}.  A current snapshot may separately expose a runtime handle
 * for compatibility with the upstream API, but historical presentation and
 * gameplay metadata always come from this frozen profile.
 */
public record EffectiveRoleProfile(
        RoleKey key,
        EffectiveRole.Source source,
        int color,
        MoodType mood,
        boolean innocent,
        boolean canUseKiller,
        boolean neutral,
        boolean vigilanteTeam,
        int defaultMax,
        int enableChance,
        int needPlayerCount,
        int maxPlayerCount,
        boolean canSeeCoin,
        boolean canPickUpRevolver,
        boolean canBeRandomed,
        int maxSprintTime,
        boolean canSeeTime,
        boolean neutralForKiller,
        boolean neutralForInnocent,
        boolean mafiaTeam,
        boolean canUseInstinct,
        boolean instinctNightVision,
        boolean canSeeTeammateKiller,
        boolean otherModeRole,
        boolean hiddenForRotation,
        int occupiedRoleCount,
        SpecialMapRoleMap specialMapRole,
        List<RoleKey> occupationKeys,
        List<RoleKey> opposingKeys,
        List<RoleKey> relatedKeys,
        /** Direction of the opposing relation wired from the opposing keys; see
         *  {@link com.habitrain.core.api.role.v2.definition.RoleRelationProfile#opposingTwoWay()}.
         *  Round-tripped by {@link #withOverlay}/{@link #toOverlay} so a rebuilt
         *  overlay no longer hard-codes two-way (review M15). */
        boolean opposingTwoWay,
        List<RoleSkillSpec> skills,
        boolean skillsSpecified,
        NamePatch namePatch,
        RoleTextPatch descriptionPatch,
        RoleTextPatch simpleDescriptionPatch,
        DefaultItemsPatch defaultItemsPatch,
        ShopPatch shopPatch,
        ShopTransform shopTransform,
        WinConditionHook winConditionHook,
        com.habitrain.core.api.role.patch.ColorPatch colorProvider,
        FlagsPatch flagsPatch,
        SpawnInfoPatch spawnInfoPatch) {

    public EffectiveRoleProfile {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mood, "mood");
        Objects.requireNonNull(specialMapRole, "specialMapRole");
        occupationKeys = List.copyOf(Objects.requireNonNullElse(occupationKeys, List.of()));
        opposingKeys = List.copyOf(Objects.requireNonNullElse(opposingKeys, List.of()));
        relatedKeys = List.copyOf(Objects.requireNonNullElse(relatedKeys, List.of()));
        skills = List.copyOf(Objects.requireNonNullElse(skills, List.of()));
    }

    /**
     * Compat constructor for the pre-{@code opposingTwoWay} signature; keeps
     * source compatibility for existing positional callers and defaults the
     * opposing relation to two-way.
     */
    public EffectiveRoleProfile(
            RoleKey key, EffectiveRole.Source source, int color, MoodType mood,
            boolean innocent, boolean canUseKiller, boolean neutral, boolean vigilanteTeam,
            int defaultMax, int enableChance, int needPlayerCount, int maxPlayerCount,
            boolean canSeeCoin, boolean canPickUpRevolver, boolean canBeRandomed,
            int maxSprintTime, boolean canSeeTime, boolean neutralForKiller, boolean neutralForInnocent,
            boolean mafiaTeam, boolean canUseInstinct, boolean instinctNightVision,
            boolean canSeeTeammateKiller, boolean otherModeRole, boolean hiddenForRotation,
            int occupiedRoleCount, SpecialMapRoleMap specialMapRole,
            List<RoleKey> occupationKeys, List<RoleKey> opposingKeys, List<RoleKey> relatedKeys,
            List<RoleSkillSpec> skills, boolean skillsSpecified,
            NamePatch namePatch, RoleTextPatch descriptionPatch, RoleTextPatch simpleDescriptionPatch,
            DefaultItemsPatch defaultItemsPatch, ShopPatch shopPatch, ShopTransform shopTransform,
            WinConditionHook winConditionHook,
            com.habitrain.core.api.role.patch.ColorPatch colorProvider,
            FlagsPatch flagsPatch, SpawnInfoPatch spawnInfoPatch) {
        this(key, source, color, mood, innocent, canUseKiller, neutral, vigilanteTeam,
                defaultMax, enableChance, needPlayerCount, maxPlayerCount,
                canSeeCoin, canPickUpRevolver, canBeRandomed,
                maxSprintTime, canSeeTime, neutralForKiller, neutralForInnocent,
                mafiaTeam, canUseInstinct, instinctNightVision,
                canSeeTeammateKiller, otherModeRole, hiddenForRotation,
                occupiedRoleCount, specialMapRole,
                occupationKeys, opposingKeys, relatedKeys, true, skills, skillsSpecified,
                namePatch, descriptionPatch, simpleDescriptionPatch,
                defaultItemsPatch, shopPatch, shopTransform, winConditionHook,
                colorProvider, flagsPatch, spawnInfoPatch);
    }

    /** Captures the baseline values exposed by the real upstream role. */
    public static EffectiveRoleProfile from(RoleKey key, SRERole role, EffectiveRole.Source source) {
        Objects.requireNonNull(role, "role");
        return new EffectiveRoleProfile(key, source,
                role.getColor(), role.getMoodType(), role.isInnocent(), role.canUseKiller(),
                role.isNeutrals(), role.isVigilanteTeam(),
                role.defaultMaxCount, role.defaultEnableChance,
                role.defaultEnableNeedPlayerCount, role.defaultEnableMaxPlayerCount,
                role.canSeeCoin(), role.canPickUpRevolver(), role.canBeRandomedDefination(),
                role.getMaxSprintTime(), role.canSeeTime(), role.isNeutralForKiller(),
                role.isNeutralForInnocent(), role.isMafiaTeam(), role.canUseInstinct(),
                role.haveInstinctNightVision(), role.canSeeTeammateKillerRole(),
                role.isOtherModeRole(), role.getFlags().contains("inner.role_rotation.hidden"),
                role.getOccupiedRoleCount(), role.getSpecialMapRole(),
                relationKeys(role.occupationRoles), relationKeys(role.opposingRoles),
                relationKeys(role.relatedRoles), true, List.of(), false,
                null, null, null, null, null, null, null,
                null, null, null);
    }

    /** Replaces all mutable gameplay fields with a compiled MODIFY result. */
    public EffectiveRoleProfile withOverlay(CompiledModifyOverlay overlay) {
        if (overlay == null) {
            return this;
        }
        return new EffectiveRoleProfile(key, source,
                overlay.color(), overlay.mood(), overlay.innocent(), overlay.canUseKiller(),
                overlay.neutral(), overlay.vigilanteTeam(), overlay.defaultMax(),
                overlay.enableChance(), overlay.needPlayerCount(), overlay.maxPlayerCount(),
                overlay.canSeeCoin(), overlay.canPickUpRevolver(), overlay.canBeRandomed(),
                overlay.maxSprintTime(), overlay.canSeeTime(), overlay.neutralForKiller(),
                overlay.neutralForInnocent(), overlay.mafiaTeam(), overlay.canUseInstinct(),
                overlay.instinctNightVision(), overlay.canSeeTeammateKiller(),
                overlay.otherModeRole(), overlay.hiddenForRotation(), overlay.occupiedRoleCount(),
                overlay.specialMapRole(), overlay.occupationKeys(), overlay.opposingKeys(),
                overlay.relatedKeys(), overlay.opposingTwoWay(), overlay.skills(), overlay.skillsSpecified(),
                overlay.namePatch(), overlay.descriptionPatch(), overlay.simpleDescriptionPatch(),
                overlay.defaultItemsPatch(), overlay.shopPatch(), overlay.shopTransform(),
                overlay.winConditionHook(),
                overlay.colorProvider(), overlay.flagsPatch(), overlay.spawnInfoPatch());
    }

    /** Rebuilds a runtime overlay without consulting a live registry or config. */
    public CompiledModifyOverlay toOverlay() {
        return new CompiledModifyOverlay(color, mood,
                colorProvider, flagsPatch, spawnInfoPatch,
                namePatch, descriptionPatch, simpleDescriptionPatch,
                defaultItemsPatch, shopPatch, shopTransform, winConditionHook,
                innocent, canUseKiller, neutral,
                vigilanteTeam, defaultMax, enableChance, needPlayerCount, maxPlayerCount,
                canSeeCoin, canPickUpRevolver, canBeRandomed, maxSprintTime, canSeeTime,
                neutralForKiller, neutralForInnocent, mafiaTeam, canUseInstinct,
                instinctNightVision, canSeeTeammateKiller, otherModeRole, hiddenForRotation,
                occupiedRoleCount, specialMapRole, occupationKeys, opposingKeys, relatedKeys,
                opposingTwoWay, skills, skillsSpecified);
    }

    private static List<RoleKey> relationKeys(java.util.Collection<SRERole> roles) {
        return roles == null ? List.of() : roles.stream()
                .filter(Objects::nonNull).map(SRERole::identifier)
                .filter(Objects::nonNull).map(RoleKey::of).toList();
    }
}
