package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.RoleRelationProfile;
import io.wifi.starrailexpress.api.SRERole;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Captures and restores the pristine {@link RoleBaseline} per role object
 * (fix-doc §4.5). Capture happens exactly once per object; {@code restore} writes
 * the captured spawn fields, relation collections and skill table back so a
 * disabled or reverted patch leaves no residue.
 */
public final class RoleBaselineStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleBaselineStore");
    private static final IdentityHashMap<SRERole, RoleBaseline> BASELINES = new IdentityHashMap<>();
    private static final IdentityHashMap<SRERole, RelationSnapshot> RELATION_SNAPSHOTS = new IdentityHashMap<>();

    /**
     * The process-wide unified-skill backend, shared by the snapshot compiler
     * (baseline capture during compile) and the runtime applier (materialization
     * and restore). {@code null} means the runtime {@code RoleSkill} table.
     */
    private static volatile @Nullable RoleRuntimeOverlayApplier.SkillBackend skillBackendOverride;

    private RoleBaselineStore() {}

    /** The effective skill backend: the test override when bound, else the runtime default. */
    public static RoleRuntimeOverlayApplier.SkillBackend skillBackend() {
        RoleRuntimeOverlayApplier.SkillBackend bound = skillBackendOverride;
        return bound != null ? bound : RoleRuntimeOverlayApplier.RUNTIME_SKILL_BACKEND;
    }

    /** Binds a different skill backend (unit tests inject a Map-backed one); {@code null} restores the runtime default. */
    public static void setSkillBackend(@Nullable RoleRuntimeOverlayApplier.SkillBackend backend) {
        skillBackendOverride = backend;
    }

    /**
     * Captures the relation graph before a v2 relation write. Upstream relation
     * setters mutate both ends (occupationed, opposing, related), so restoring
     * only the target role would leave reverse references behind. This captures
     * the target plus every resolved counterpart that the relation profile can
     * touch, once per object, and {@link #restoreAll} restores all of them.
     */
    public static void captureRelationGraph(SRERole self, RoleRelationProfile profile,
                                            Function<RoleKey, SRERole> resolve) {
        if (self == null || profile == null) {
            return;
        }
        captureRelation(self);
        if (resolve == null) {
            return;
        }
        for (RoleKey key : profile.occupation()) {
            SRERole other = resolve.apply(key);
            if (other != null) {
                captureRelation(other);
            }
        }
        for (RoleKey key : profile.opposing()) {
            SRERole other = resolve.apply(key);
            if (other != null) {
                captureRelation(other);
            }
        }
        for (RoleKey key : profile.related()) {
            SRERole other = resolve.apply(key);
            if (other != null) {
                captureRelation(other);
            }
        }
    }

    private static void captureRelation(SRERole role) {
        if (role == null || RELATION_SNAPSHOTS.containsKey(role)) {
            return;
        }
        RELATION_SNAPSHOTS.put(role, new RelationSnapshot(
                new ArrayList<>(role.occupationRoles),
                new HashSet<>(role.occupationedRoles),
                new HashSet<>(role.opposingRoles),
                new HashSet<>(role.relatedRoles)));
    }

    private static void restoreRelations() {
        for (var e : RELATION_SNAPSHOTS.entrySet()) {
            SRERole role = e.getKey();
            RelationSnapshot snap = e.getValue();
            role.occupationRoles.clear();
            role.occupationRoles.addAll(snap.occupationRoles());
            role.occupationedRoles.clear();
            role.occupationedRoles.addAll(snap.occupationedRoles());
            role.opposingRoles.clear();
            role.opposingRoles.addAll(snap.opposingRoles());
            role.relatedRoles.clear();
            role.relatedRoles.addAll(snap.relatedRoles());
        }
    }

    /**
     * The captured baseline for {@code role} using the shared
     * {@link #skillBackend()}, capturing it now on first use. Identity-keyed:
     * the first capture of an object is its pristine baseline; later captures
     * (even after an overlay mutated the live role) return the same pristine
     * values, which is what makes compile → activate → compile free of
     * patch accumulation.
     */
    public static RoleBaseline getOrCapture(SRERole role) {
        return getOrCapture(role, skillBackend());
    }

    /**
     * Captures a pristine baseline now for every non-null role with an id.
     * Called before the first v2 overlay activation (snapshot compile entry),
     * so a role that is mutated later always has an immutable baseline.
     */
    public static void captureAll(Collection<? extends SRERole> roles) {
        if (roles == null) {
            return;
        }
        for (SRERole role : roles) {
            if (role != null && role.identifier() != null) {
                getOrCapture(role);
            }
        }
    }

    /**
     * The captured baseline for {@code role}, capturing it now on first use.
     * {@code skillBackend} reads the unified skill table; a failure there (e.g.
     * an un-bootstrapped unit-test JVM) degrades to an empty skill snapshot.
     */
    public static RoleBaseline getOrCapture(SRERole role, RoleRuntimeOverlayApplier.SkillBackend skillBackend) {
        RoleBaseline existing = BASELINES.get(role);
        if (existing != null) {
            return existing;
        }
        List<io.wifi.starrailexpress.api.RoleSkill.Definition> skills = List.of();
        if (skillBackend != null) {
            try {
                skills = skillBackend.definitions(role.identifier());
            } catch (Throwable t) {
                LOGGER.debug("Skill baseline unavailable for {}: {}", role.identifier(), t.toString());
            }
        }
        RoleBaseline baseline = new RoleBaseline(
                role,
                role.defaultMaxCount,
                role.defaultEnableChance,
                role.defaultEnableNeedPlayerCount,
                role.defaultEnableMaxPlayerCount,
                role.getColor(),
                role.getMoodType(),
                role.isInnocent(),
                role.canUseKiller(),
                role.isNeutrals(),
                role.isVigilanteTeam(),
                role.canSeeCoin(),
                role.canPickUpRevolver(),
                role.canBeRandomedDefination(),
                role.getMaxSprintTime(),
                role.canSeeTime(),
                role.isNeutralForKiller(),
                role.isNeutralForInnocent(),
                role.isMafiaTeam(),
                role.canUseInstinct(),
                role.haveInstinctNightVision(),
                role.canSeeTeammateKillerRole(),
                role.isOtherModeRole(),
                role.getFlags().contains("inner.role_rotation.hidden"),
                role.getOccupiedRoleCount(),
                role.getSpecialMapRole(),
                new HashSet<>(role.getFlags()),
                new ArrayList<>(role.occupationRoles),
                new HashSet<>(role.occupationedRoles),
                new HashSet<>(role.opposingRoles),
                new HashSet<>(role.relatedRoles),
                skills == null ? List.of() : skills);
        BASELINES.put(role, baseline);
        return baseline;
    }

    /** The captured baseline, or {@code null} if this object was never captured. */
    public static RoleBaseline baseline(SRERole role) {
        return BASELINES.get(role);
    }

    /** Restores the baseline values onto the object and keeps the baseline for reuse. */
    public static void restore(SRERole role, RoleRuntimeOverlayApplier.SkillBackend skillBackend) {
        RoleBaseline baseline = BASELINES.get(role);
        if (baseline == null || role != baseline.role()) {
            return;
        }
        role.defaultMaxCount = baseline.defaultMaxCount();
        role.defaultEnableChance = baseline.defaultEnableChance();
        role.defaultEnableNeedPlayerCount = baseline.defaultEnableNeedPlayerCount();
        role.defaultEnableMaxPlayerCount = baseline.defaultEnableMaxPlayerCount();
        role.setColor(baseline.color());
        role.setMoodType(baseline.mood());
        role.setInnocent(baseline.innocent());
        role.setCanUseKiller(baseline.canUseKiller());
        role.setVigilanteTeam(baseline.vigilanteTeam());
        role.setCanSeeCoin(baseline.canSeeCoin());
        role.setCanPickUpRevolver(baseline.canPickUpRevolver());
        role.setCanBeRandomedByOtherRoles(baseline.canBeRandomed());
        role.setMaxSprintTime(baseline.maxSprintTime());
        role.setCanSeeTime(baseline.canSeeTime());
        role.setNeutralForKiller(baseline.neutralForKiller());
        role.setNeutralForInnocent(baseline.neutralForInnocent());
        role.setNeutrals(baseline.neutral());
        role.setMafiaTeam(baseline.mafiaTeam());
        role.setCanUseInstinct(baseline.canUseInstinct());
        role.setInstinctNightVision(baseline.instinctNightVision());
        role.setCanSeeTeammateKillerRole(baseline.canSeeTeammateKiller());
        role.setOtherModeRole(baseline.otherModeRole());
        role.setHiddenForRoleRotation(baseline.hiddenForRotation());
        role.setOccupiedRoleCount(baseline.occupiedRoleCount());
        role.setSpecialMapRole(baseline.specialMapRole());
        // Some upstream fluent setters unconditionally add their marker flags.
        // Restore the exact baseline after using them so a disabled overlay has
        // no residual rotation/mafia/other-mode visibility state.
        role.getFlags().clear();
        role.getFlags().addAll(baseline.flags());
        resetRelations(role);
        addAll(role.occupationRoles, baseline.occupationRoles());
        role.occupationedRoles.addAll(baseline.occupationedRoles());
        role.opposingRoles.addAll(baseline.opposingRoles());
        role.relatedRoles.addAll(baseline.relatedRoles());
        if (skillBackend != null) {
            try {
                skillBackend.replace(role.identifier(), baseline.skills());
            } catch (Throwable t) {
                LOGGER.debug("Skill baseline restore unavailable for {}: {}", role.identifier(), t.toString());
            }
        }
    }

    /** Restores every captured object, keeping the baselines for later re-activation. */
    public static void restoreAll(RoleRuntimeOverlayApplier.SkillBackend skillBackend) {
        for (SRERole role : new ArrayList<>(BASELINES.keySet())) {
            restore(role, skillBackend);
        }
        restoreRelations();
    }

    /** Drops every captured baseline and restores the runtime skill backend (server stop, test isolation). */
    public static void clear() {
        BASELINES.clear();
        RELATION_SNAPSHOTS.clear();
        skillBackendOverride = null;
    }

    private static void resetRelations(SRERole role) {
        role.occupationRoles.clear();
        role.occupationedRoles.clear();
        role.opposingRoles.clear();
        role.relatedRoles.clear();
    }

    private static void addAll(List<SRERole> target, List<SRERole> source) {
        for (SRERole r : source) {
            if (r != null && !target.contains(r)) {
                target.add(r);
            }
        }
    }

    private record RelationSnapshot(
            List<SRERole> occupationRoles,
            Set<SRERole> occupationedRoles,
            Set<SRERole> opposingRoles,
            Set<SRERole> relatedRoles) {}
}
