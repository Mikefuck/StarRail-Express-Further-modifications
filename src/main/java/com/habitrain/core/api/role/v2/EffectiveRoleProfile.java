package com.habitrain.core.api.role.v2;

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
        List<RoleSkillSpec> skills,
        boolean skillsSpecified) {

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
                relationKeys(role.relatedRoles), List.of(), false);
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
                overlay.relatedKeys(), overlay.skills(), overlay.skillsSpecified());
    }

    /** Rebuilds a runtime overlay without consulting a live registry or config. */
    public CompiledModifyOverlay toOverlay() {
        return new CompiledModifyOverlay(color, mood, innocent, canUseKiller, neutral,
                vigilanteTeam, defaultMax, enableChance, needPlayerCount, maxPlayerCount,
                canSeeCoin, canPickUpRevolver, canBeRandomed, maxSprintTime, canSeeTime,
                neutralForKiller, neutralForInnocent, mafiaTeam, canUseInstinct,
                instinctNightVision, canSeeTeammateKiller, otherModeRole, hiddenForRotation,
                occupiedRoleCount, specialMapRole, occupationKeys, opposingKeys, relatedKeys,
                true, skills, skillsSpecified);
    }

    private static List<RoleKey> relationKeys(java.util.Collection<SRERole> roles) {
        return roles == null ? List.of() : roles.stream()
                .filter(Objects::nonNull).map(SRERole::identifier)
                .filter(Objects::nonNull).map(RoleKey::of).toList();
    }
}
