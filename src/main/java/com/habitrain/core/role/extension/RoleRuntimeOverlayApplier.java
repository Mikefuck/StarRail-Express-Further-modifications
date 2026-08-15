package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RoleRelationProfile;
import com.habitrain.core.game.sre.mixin.RoleSkillUnifiedSkillsAccessor;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Materializes a v2 {@code MODIFY} overlay onto the ORIGINAL role object
 * (fix-doc §5.3). The role keeps its identity, component key and subclass
 * behavior; only the public spawn fields are written, relations are linked onto
 * the same object, the unified skill table is atomically replaced, and the
 * getter-based values stay available through {@link RoleOverlayAccessor}.
 *
 * <p>All operations are idempotent: {@link RoleBaselineStore} captures the
 * pristine object once, so repeated {@code applyModifiesAndReturn} calls converge
 * to the same final values and {@code restoreAll}/{@code serverStop} remove all
 * residue.
 */
public final class RoleRuntimeOverlayApplier {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleRuntimeOverlayApplier");

    /** Unified-skill read/write seam; the runtime default reads {@code RoleSkill}. */
    public interface SkillBackend {
        List<RoleSkill.Definition> definitions(ResourceLocation roleId);

        void replace(ResourceLocation roleId, List<RoleSkill.Definition> definitions);
    }

    /** Runtime backend bound to the upstream {@code RoleSkill} table. */
    public static final class RoleSkillBackend implements SkillBackend {
        @Override
        public List<RoleSkill.Definition> definitions(ResourceLocation roleId) {
            return roleId == null ? List.of() : RoleSkill.getDefinitions(roleId);
        }

        @Override
        public void replace(ResourceLocation roleId, List<RoleSkill.Definition> definitions) {
            if (roleId == null) {
                return;
            }
            List<RoleSkill.Definition> safe = definitions == null ? List.of() : List.copyOf(definitions);
            // RoleSkill.register rejects an empty vararg list.  Go through the
            // same accessor used by the legacy override manager so REPLACE_ALL
            // can represent an intentional empty list and baseline restoration
            // can faithfully restore a role that started without skills.
            Map<ResourceLocation, List<RoleSkill.Definition>> unified =
                    RoleSkillUnifiedSkillsAccessor.habitrain$getUnifiedSkills();
            Map<ResourceLocation, RoleSkill.SkillEntry> registry =
                    RoleSkillUnifiedSkillsAccessor.habitrain$getSkillRegistry();
            registry.entrySet().removeIf(entry -> roleId.equals(entry.getValue().roleId()));
            if (safe.isEmpty()) {
                unified.remove(roleId);
            } else {
                RoleSkill.register(roleId, safe.toArray(RoleSkill.Definition[]::new));
            }
        }
    }

    private static final SkillBackend RUNTIME_SKILL_BACKEND = new RoleSkillBackend();
    private static volatile SkillBackend skillBackend = RUNTIME_SKILL_BACKEND;
    private static volatile @Nullable Function<RoleKey, SRERole> relationResolver;

    private RoleRuntimeOverlayApplier() {}

    /** Binds a different skill backend (unit tests inject a Map-backed one). */
    public static void setSkillBackend(@Nullable SkillBackend backend) {
        skillBackend = backend == null ? RUNTIME_SKILL_BACKEND : backend;
    }

    /**
     * Binds how relation {@link RoleKey}s resolve to roles when linking MODIFY
     * relation patches. {@code null} (default) skips physical linking; consumers
     * then read the overlay's relation keys.
     */
    public static void setRelationResolver(@Nullable Function<RoleKey, SRERole> resolver) {
        relationResolver = resolver;
    }

    /**
     * The new core of {@code RoleExtensionRegistry.applyModifies}: returns the
     * ORIGINAL role object (never a wrapper), having written the four public
     * spawn fields, linked relations and replaced skills per the folded overlay.
     * Only config-enabled patches are folded (fix-doc §13.1).
     */
    public static SRERole applyModifiesAndReturn(SRERole role) {
        if (role == null) {
            return role;
        }
        return applyModifiesAndReturnConfigured(role,
                RoleExtensionRegistry.INSTANCE.configuredPatchesFor(role.identifier()));
    }

    /**
     * {@link #applyModifiesAndReturn(SRERole)} against an explicit configured patch
     * list (patches paired with entryIds so config conflict winners apply).
     */
    public static SRERole applyModifiesAndReturnConfigured(SRERole role, @Nullable List<ConfiguredPatch> patches) {
        if (role == null || role.identifier() == null) {
            return role;
        }
        if (patches == null || patches.isEmpty()) {
            RoleOverlayAccessor.remove(role);
            return role;
        }
        RoleBaseline baseline = RoleBaselineStore.getOrCapture(role, skillBackend);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlayConfigured(role, patches, baseline);
        return materialize(role, baseline, overlay);
    }

    /** {@link #applyModifiesAndReturn(SRERole)} against an explicit bare patch list (unit tests / legacy callers). */
    public static SRERole applyModifiesAndReturn(SRERole role, @Nullable List<RolePatch> patches) {
        if (role == null || role.identifier() == null) {
            return role;
        }
        if (patches == null || patches.isEmpty()) {
            RoleOverlayAccessor.remove(role);
            return role;
        }
        RoleBaseline baseline = RoleBaselineStore.getOrCapture(role, skillBackend);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(role, patches, baseline);
        return materialize(role, baseline, overlay);
    }

    private static SRERole materialize(SRERole role, RoleBaseline baseline, CompiledModifyOverlay overlay) {
        applyRoleFields(role, overlay);
        linkRelations(role, overlay);
        replaceSkills(role.identifier(), overlay);
        RoleOverlayAccessor.set(role, overlay);
        return role;
    }

    /**
     * Materializes every modified role of the snapshot onto its original object.
     * Restores the previous overlay's writes first, so toggling snapshots never
     * accumulates. Called when a lobby/round/pending snapshot becomes current.
     */
    public static void activate(@Nullable RoleSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        restoreAll();
        captureRelationGraphForSnapshot(snapshot);
        linkActiveRelations();
        for (var er : snapshot.effectiveRoles()) {
            SRERole role = er.role();
            if (role == null || role.identifier() == null) {
                continue;
            }
            CompiledModifyOverlay overlay = er.modifyOverlay();
            if (overlay != null) {
                RoleBaseline baseline = RoleBaselineStore.getOrCapture(role, skillBackend);
                materialize(role, baseline, overlay);
            }
        }
    }

    private static void captureRelationGraphForSnapshot(RoleSnapshot snapshot) {
        Function<RoleKey, SRERole> resolver = relationResolver;
        if (resolver == null) {
            return;
        }
        for (var link : RoleExtensionRegistry.INSTANCE.activeRelationLinks()) {
            RoleBaselineStore.captureRelationGraph(link.role(), link.profile(), resolver);
        }
        for (var er : snapshot.effectiveRoles()) {
            CompiledModifyOverlay overlay = er.modifyOverlay();
            if (overlay == null || !overlay.hasRelationKeys()) {
                continue;
            }
            RoleRelationProfile profile = new RoleRelationProfile(
                    overlay.occupationKeys(), overlay.opposingKeys(), overlay.relatedKeys(),
                    overlay.opposingTwoWay());
            RoleBaselineStore.captureRelationGraph(er.role(), profile, resolver);
        }
    }

    private static void linkActiveRelations() {
        Function<RoleKey, SRERole> resolver = relationResolver;
        if (resolver == null) {
            return;
        }
        for (var link : RoleExtensionRegistry.INSTANCE.activeRelationLinks()) {
            try {
                RoleExtensionCompiler.linkRelations(link.role(), link.profile(), resolver);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to link ADD/REPLACE relations for {}: {}",
                        link.role().identifier(), e.toString());
            }
        }
    }

    /** Restores every captured baseline (values back to pristine, overlays dropped). */
    public static void restoreAll() {
        RoleBaselineStore.restoreAll(skillBackend);
        RoleOverlayAccessor.clear();
    }

    /** Server-stop cleanup: restore baselines and drop all session state. */
    public static void serverStop() {
        restoreAll();
        RoleBaselineStore.clear();
    }

    /** Test isolation: drop baselines, overlays and any injected seam. */
    public static void clear() {
        RoleBaselineStore.clear();
        RoleOverlayAccessor.clear();
        skillBackend = RUNTIME_SKILL_BACKEND;
        relationResolver = null;
    }

    /**
     * Writes every public v2 MODIFY field through SRERole's own mutators.  This
     * is deliberately broader than spawn fields: gameplay consumers call the
     * upstream getters directly, so retaining values only in an overlay map is
     * not sufficient.  {@link RoleBaselineStore} captures the matching values
     * before the first write and restores them on a snapshot transition.
     */
    private static void applyRoleFields(SRERole role, CompiledModifyOverlay overlay) {
        role.setColor(overlay.color());
        role.setMoodType(overlay.mood());
        role.setInnocent(overlay.innocent());
        role.setCanUseKiller(overlay.canUseKiller());
        role.setVigilanteTeam(overlay.vigilanteTeam());
        role.setDefaultMax(overlay.defaultMax());
        role.setDefaultEnableChance(overlay.enableChance());
        role.setDefaultEnableNeededPlayerCount(overlay.needPlayerCount());
        role.setDefaultEnableMaxPlayerCount(overlay.maxPlayerCount());
        role.setCanSeeCoin(overlay.canSeeCoin());
        role.setCanPickUpRevolver(overlay.canPickUpRevolver());
        role.setCanBeRandomedByOtherRoles(overlay.canBeRandomed());
        role.setMaxSprintTime(overlay.maxSprintTime());
        role.setCanSeeTime(overlay.canSeeTime());
        role.setNeutralForKiller(overlay.neutralForKiller());
        role.setNeutralForInnocent(overlay.neutralForInnocent());
        // These two upstream setters force the neutral bit on, so set the
        // explicit final value afterwards instead of letting setter order leak
        // into the compiled profile.
        role.setNeutrals(overlay.neutral());
        role.setMafiaTeam(overlay.mafiaTeam());
        role.setCanUseInstinct(overlay.canUseInstinct());
        role.setInstinctNightVision(overlay.instinctNightVision());
        role.setCanSeeTeammateKillerRole(overlay.canSeeTeammateKiller());
        role.setOtherModeRole(overlay.otherModeRole());
        role.setHiddenForRoleRotation(overlay.hiddenForRotation());
        role.setOccupiedRoleCount(overlay.occupiedRoleCount());
        role.setSpecialMapRole(overlay.specialMapRole());
    }

    private static void linkRelations(SRERole role, CompiledModifyOverlay overlay) {
        Function<RoleKey, SRERole> resolver = relationResolver;
        if (resolver == null) {
            return;
        }
        RoleRelationProfile profile = new RoleRelationProfile(
                overlay.occupationKeys(), overlay.opposingKeys(), overlay.relatedKeys(),
                overlay.opposingTwoWay());
        RoleBaselineStore.captureRelationGraph(role, profile, resolver);
        try {
            RoleExtensionCompiler.linkRelations(role, profile, resolver);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to link MODIFY relations for {}: {}", role.identifier(), e.toString());
        }
    }

    private static void replaceSkills(ResourceLocation roleId, CompiledModifyOverlay overlay) {
        List<RoleSkill.Definition> defs = new ArrayList<>();
        for (var spec : overlay.skills()) {
            if (spec != null && spec.definition() != null) {
                defs.add(spec.definition());
            }
        }
        if (!overlay.skillsSpecified()) {
            return;
        }
        try {
            skillBackend.replace(roleId, defs);
        } catch (Throwable t) {
            LOGGER.warn("Failed to apply MODIFY skills for {}: {}", roleId, t.toString());
        }
    }
}
