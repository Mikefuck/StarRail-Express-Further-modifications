package com.habitrain.core.role.extension;

import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.SRERole.MoodType;
import io.wifi.starrailexpress.api.SRERole.SpecialMapRoleMap;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;

import java.util.List;
import java.util.Set;

/**
 * A one-time capture of a role object's pristine values before the first v2
 * {@code MODIFY} overlay is applied (fix-doc §4.5). Bound to the object instance
 * (not the id), so re-activation after a toggle reuses the original baseline and
 * never re-captures already-modified values.
 *
 * <p>Captures the public spawn fields, flags, the four relation collections and
 * the unified-skill table. Skills are captured through the applier's injectable
 * {@code SkillBackend} so unit tests stay bootstrap-safe.
 */
public final class RoleBaseline {

    private final SRERole role;
    private final int defaultMaxCount;
    private final int defaultEnableChance;
    private final int defaultEnableNeedPlayerCount;
    private final int defaultEnableMaxPlayerCount;
    private final int color;
    private final MoodType mood;
    private final boolean innocent;
    private final boolean canUseKiller;
    private final boolean neutral;
    private final boolean vigilanteTeam;
    private final boolean canSeeCoin;
    private final boolean canPickUpRevolver;
    private final boolean canBeRandomed;
    private final int maxSprintTime;
    private final boolean canSeeTime;
    private final boolean neutralForKiller;
    private final boolean neutralForInnocent;
    private final boolean mafiaTeam;
    private final boolean canUseInstinct;
    private final boolean instinctNightVision;
    private final boolean canSeeTeammateKiller;
    private final boolean otherModeRole;
    private final boolean hiddenForRotation;
    private final int occupiedRoleCount;
    private final SpecialMapRoleMap specialMapRole;
    private final Set<String> flags;
    private final List<SRERole> occupationRoles;
    private final Set<SRERole> occupationedRoles;
    private final Set<SRERole> opposingRoles;
    private final Set<SRERole> relatedRoles;
    private final List<RoleSkill.Definition> skills;

    RoleBaseline(SRERole role,
                 int defaultMaxCount, int defaultEnableChance,
                 int defaultEnableNeedPlayerCount, int defaultEnableMaxPlayerCount,
                 int color, MoodType mood, boolean innocent, boolean canUseKiller,
                 boolean neutral, boolean vigilanteTeam, boolean canSeeCoin,
                 boolean canPickUpRevolver, boolean canBeRandomed, int maxSprintTime,
                 boolean canSeeTime, boolean neutralForKiller, boolean neutralForInnocent,
                 boolean mafiaTeam, boolean canUseInstinct, boolean instinctNightVision,
                 boolean canSeeTeammateKiller, boolean otherModeRole,
                 boolean hiddenForRotation, int occupiedRoleCount,
                 SpecialMapRoleMap specialMapRole,
                 Set<String> flags,
                 List<SRERole> occupationRoles,
                 Set<SRERole> occupationedRoles,
                 Set<SRERole> opposingRoles,
                 Set<SRERole> relatedRoles,
                 List<RoleSkill.Definition> skills) {
        this.role = role;
        this.defaultMaxCount = defaultMaxCount;
        this.defaultEnableChance = defaultEnableChance;
        this.defaultEnableNeedPlayerCount = defaultEnableNeedPlayerCount;
        this.defaultEnableMaxPlayerCount = defaultEnableMaxPlayerCount;
        this.color = color;
        this.mood = mood;
        this.innocent = innocent;
        this.canUseKiller = canUseKiller;
        this.neutral = neutral;
        this.vigilanteTeam = vigilanteTeam;
        this.canSeeCoin = canSeeCoin;
        this.canPickUpRevolver = canPickUpRevolver;
        this.canBeRandomed = canBeRandomed;
        this.maxSprintTime = maxSprintTime;
        this.canSeeTime = canSeeTime;
        this.neutralForKiller = neutralForKiller;
        this.neutralForInnocent = neutralForInnocent;
        this.mafiaTeam = mafiaTeam;
        this.canUseInstinct = canUseInstinct;
        this.instinctNightVision = instinctNightVision;
        this.canSeeTeammateKiller = canSeeTeammateKiller;
        this.otherModeRole = otherModeRole;
        this.hiddenForRotation = hiddenForRotation;
        this.occupiedRoleCount = occupiedRoleCount;
        this.specialMapRole = specialMapRole;
        this.flags = Set.copyOf(flags);
        this.occupationRoles = List.copyOf(occupationRoles);
        this.occupationedRoles = Set.copyOf(occupationedRoles);
        this.opposingRoles = Set.copyOf(opposingRoles);
        this.relatedRoles = Set.copyOf(relatedRoles);
        this.skills = List.copyOf(skills);
    }

    /** The object this baseline belongs to. */
    public SRERole role() { return role; }
    public int defaultMaxCount() { return defaultMaxCount; }
    public int defaultEnableChance() { return defaultEnableChance; }
    public int defaultEnableNeedPlayerCount() { return defaultEnableNeedPlayerCount; }
    public int defaultEnableMaxPlayerCount() { return defaultEnableMaxPlayerCount; }
    public int color() { return color; }
    public MoodType mood() { return mood; }
    public boolean innocent() { return innocent; }
    public boolean canUseKiller() { return canUseKiller; }
    public boolean neutral() { return neutral; }
    public boolean vigilanteTeam() { return vigilanteTeam; }
    public boolean canSeeCoin() { return canSeeCoin; }
    public boolean canPickUpRevolver() { return canPickUpRevolver; }
    public boolean canBeRandomed() { return canBeRandomed; }
    public int maxSprintTime() { return maxSprintTime; }
    public boolean canSeeTime() { return canSeeTime; }
    public boolean neutralForKiller() { return neutralForKiller; }
    public boolean neutralForInnocent() { return neutralForInnocent; }
    public boolean mafiaTeam() { return mafiaTeam; }
    public boolean canUseInstinct() { return canUseInstinct; }
    public boolean instinctNightVision() { return instinctNightVision; }
    public boolean canSeeTeammateKiller() { return canSeeTeammateKiller; }
    public boolean otherModeRole() { return otherModeRole; }
    public boolean hiddenForRotation() { return hiddenForRotation; }
    public int occupiedRoleCount() { return occupiedRoleCount; }
    public SpecialMapRoleMap specialMapRole() { return specialMapRole; }
    public Set<String> flags() { return flags; }
    public List<SRERole> occupationRoles() { return occupationRoles; }
    public Set<SRERole> occupationedRoles() { return occupationedRoles; }
    public Set<SRERole> opposingRoles() { return opposingRoles; }
    public Set<SRERole> relatedRoles() { return relatedRoles; }
    public List<RoleSkill.Definition> skills() { return skills; }

    /** Converts captured upstream skills into their stable v2 representation. */
    public List<RoleSkillSpec> skillSpecs() {
        return skills.stream().filter(java.util.Objects::nonNull)
                .filter(def -> def.id() != null)
                .map(RoleSkillSpec::of).toList();
    }

    /** Immutable relation ids for pure snapshot folding. */
    public List<RoleKey> occupationKeys() { return keys(occupationRoles); }
    public List<RoleKey> opposingKeys() { return keys(opposingRoles); }
    public List<RoleKey> relatedKeys() { return keys(relatedRoles); }

    private static List<RoleKey> keys(java.util.Collection<SRERole> roles) {
        return roles.stream().filter(java.util.Objects::nonNull)
                .map(SRERole::identifier).filter(java.util.Objects::nonNull)
                .map(RoleKey::of).toList();
    }
}
