package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RoleRelationProfile;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.SRERole.MoodType;
import io.wifi.starrailexpress.api.SRERole.SpecialMapRoleMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Compiles v2 {@code MODIFY} patches and {@code REPLACE} definitions.
 *
 * <p>{@link #compileModifyOverlay} folds a list of {@link RolePatch}es (already
 * sorted by priority, provider, entryKey) into a pure-data
 * {@link CompiledModifyOverlay} that the runtime applier materializes onto the
 * original role object. {@link #compileReplacement} compiles a replacement
 * {@link RoleDefinition} into a {@link ManagedSRERole}. {@link #linkRelations}
 * resolves stored {@link RoleKey} lists onto upstream relation setters. All are
 * pure: they never touch {@code TMMRoles}.
 *
 * <p>The {@link ConfiguredPatch} overload additionally honors the v2 config's
 * per-{@code target#field} conflict winners: when a winner entryId is configured
 * for a field, every other patch setting that field is masked from it.
 */
public final class RoleExtensionCompiler {

    private RoleExtensionCompiler() {}

    // Field keys, matching the fix-doc §13.1 conflict-winner syntax `target#field`.
    public static final String FIELD_COLOR = "presentation.color";
    public static final String FIELD_MOOD = "presentation.mood";
    public static final String FIELD_INNOCENT = "faction.innocent";
    public static final String FIELD_CAN_USE_KILLER = "faction.canUseKiller";
    public static final String FIELD_NEUTRAL = "faction.neutral";
    public static final String FIELD_VIGILANTE_TEAM = "faction.vigilanteTeam";
    public static final String FIELD_DEFAULT_MAX = "spawn.defaultMax";
    public static final String FIELD_ENABLE_CHANCE = "spawn.enableChance";
    public static final String FIELD_NEED_PLAYER_COUNT = "spawn.enableNeedPlayerCount";
    public static final String FIELD_MAX_PLAYER_COUNT = "spawn.enableMaxPlayerCount";
    public static final String FIELD_CAN_SEE_COIN = "ability.canSeeCoin";
    public static final String FIELD_CAN_PICKUP_REVOLVER = "ability.canPickUpRevolver";
    public static final String FIELD_CAN_BE_RANDOMED = "ability.canBeRandomed";
    public static final String FIELD_MAX_SPRINT_TIME = "ability.maxSprintTime";
    public static final String FIELD_CAN_SEE_TIME = "ability.canSeeTime";
    public static final String FIELD_NEUTRAL_FOR_KILLER = "faction.neutralForKiller";
    public static final String FIELD_NEUTRAL_FOR_INNOCENT = "faction.neutralForInnocent";
    public static final String FIELD_MAFIA_TEAM = "faction.mafiaTeam";
    public static final String FIELD_CAN_USE_INSTINCT = "ability.canUseInstinct";
    public static final String FIELD_INSTINCT_NIGHT_VISION = "ability.instinctNightVision";
    public static final String FIELD_CAN_SEE_TEAMMATE_KILLER = "ability.canSeeTeammateKiller";
    public static final String FIELD_OTHER_MODE_ROLE = "ability.otherModeRole";
    public static final String FIELD_HIDDEN_FOR_ROTATION = "ability.hiddenForRotation";
    public static final String FIELD_OCCUPIED_ROLE_COUNT = "ability.occupiedRoleCount";
    public static final String FIELD_SPECIAL_MAP_ROLE = "ability.specialMapRole";
    public static final String FIELD_OCCUPATION = "relation.occupation";
    public static final String FIELD_OPPOSING = "relation.opposing";
    public static final String FIELD_RELATED = "relation.related";

    /**
     * Folds the given patches (in application order) into a pure-data
     * {@link CompiledModifyOverlay} without touching the base object. Returns
     * {@code null} when there are no patches.
     *
     * <p>{@code baseline} (when present) seeds the public spawn fields so repeated
     * application after a toggle converges instead of accumulating ADD/MIN/MAX
     * operations; getter values always seed fresh from {@code base}.
     */
    public static @Nullable CompiledModifyOverlay compileModifyOverlay(
            SRERole base, @Nullable List<RolePatch> patches, @Nullable RoleBaseline baseline) {
        return compileModifyOverlay(base, patches, baseline, null);
    }

    /**
     * Folds configured patches (paired with their entryIds) so the config's
     * per-{@code target#field} conflict winners can mask the losing entries'
     * contributions. Same shape as the plain overload when no winners exist.
     */
    public static @Nullable CompiledModifyOverlay compileModifyOverlayConfigured(
            SRERole base, @Nullable List<ConfiguredPatch> patches, @Nullable RoleBaseline baseline) {
        if (base == null || patches == null || patches.isEmpty()) {
            return null;
        }
        Function<RolePatch, Set<String>> fieldMask =
                buildFieldMask(base.identifier(), patches);
        FoldedState s = fold(base, patches.stream().map(ConfiguredPatch::patch).toList(),
                baseline, fieldMask);
        return new CompiledModifyOverlay(
                s.color(), s.mood(), s.innocent(), s.canUseKiller(), s.neutral(), s.vigilanteTeam(),
                s.defaultMax(), s.enableChance(), s.needPlayerCount(), s.maxPlayerCount(),
                s.canSeeCoin(), s.canPickUpRevolver(), s.canBeRandomed(), s.maxSprintTime(), s.canSeeTime(),
                s.neutralForKiller(), s.neutralForInnocent(), s.mafiaTeam(),
                s.canUseInstinct(), s.instinctNightVision(), s.canSeeTeammateKiller(),
                s.otherModeRole(), s.hiddenForRotation(), s.occupiedRoleCount(), s.specialMapRole(),
                s.occupation(), s.opposing(), s.related(), true, s.skills(), s.skillsSpecified());
    }

    /** Internal fold used by the plain overload (no conflict-winner masking). */
    private static @Nullable CompiledModifyOverlay compileModifyOverlay(
            SRERole base, @Nullable List<RolePatch> patches, @Nullable RoleBaseline baseline,
            @Nullable Function<RolePatch, Set<String>> fieldMask) {
        if (base == null || patches == null || patches.isEmpty()) {
            return null;
        }
        FoldedState s = fold(base, patches, baseline, fieldMask);
        return new CompiledModifyOverlay(
                s.color(), s.mood(), s.innocent(), s.canUseKiller(), s.neutral(), s.vigilanteTeam(),
                s.defaultMax(), s.enableChance(), s.needPlayerCount(), s.maxPlayerCount(),
                s.canSeeCoin(), s.canPickUpRevolver(), s.canBeRandomed(), s.maxSprintTime(), s.canSeeTime(),
                s.neutralForKiller(), s.neutralForInnocent(), s.mafiaTeam(),
                s.canUseInstinct(), s.instinctNightVision(), s.canSeeTeammateKiller(),
                s.otherModeRole(), s.hiddenForRotation(), s.occupiedRoleCount(), s.specialMapRole(),
                s.occupation(), s.opposing(), s.related(), true, s.skills(), s.skillsSpecified());
    }

    /**
     * Per-patch field mask honoring the config's conflict winners for the target:
     * for each field a patch sets, if a winner entryId is configured and it is not
     * this patch's entryId, that field is masked from this patch.
     */
    private static Function<RolePatch, Set<String>> buildFieldMask(
            ResourceLocation target, List<ConfiguredPatch> patches) {
        Map<RolePatch, String> entryOf = new IdentityHashMap<>();
        for (ConfiguredPatch cp : patches) {
            entryOf.put(cp.patch(), cp.entryId());
        }
        return patch -> {
            String entryId = entryOf.get(patch);
            if (entryId == null) {
                return Set.of();
            }
            Set<String> masked = new HashSet<>();
            for (String field : fieldsSetBy(patch)) {
                String winner = RoleExtensionConfigService.INSTANCE.winnerFor(target, field);
                if (winner != null && !winner.equals(entryId)) {
                    masked.add(field);
                }
            }
            return masked;
        };
    }

    /** The field keys a patch sets (drives conflict-winner masking and trace). */
    public static Set<String> fieldsSetBy(RolePatch patch) {
        if (patch == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        if (patch.color() != null) out.add(FIELD_COLOR);
        if (patch.mood() != null) out.add(FIELD_MOOD);
        if (patch.innocent() != null) out.add(FIELD_INNOCENT);
        if (patch.canUseKiller() != null) out.add(FIELD_CAN_USE_KILLER);
        if (patch.neutral() != null) out.add(FIELD_NEUTRAL);
        if (patch.vigilanteTeam() != null) out.add(FIELD_VIGILANTE_TEAM);
        if (patch.defaultMax() != null) out.add(FIELD_DEFAULT_MAX);
        if (patch.enableChance() != null) out.add(FIELD_ENABLE_CHANCE);
        if (patch.needPlayerCount() != null) out.add(FIELD_NEED_PLAYER_COUNT);
        if (patch.maxPlayerCount() != null) out.add(FIELD_MAX_PLAYER_COUNT);
        if (patch.canSeeCoin() != null) out.add(FIELD_CAN_SEE_COIN);
        if (patch.canPickUpRevolver() != null) out.add(FIELD_CAN_PICKUP_REVOLVER);
        if (patch.canBeRandomed() != null) out.add(FIELD_CAN_BE_RANDOMED);
        if (patch.maxSprintTime() != null) out.add(FIELD_MAX_SPRINT_TIME);
        if (patch.canSeeTime() != null) out.add(FIELD_CAN_SEE_TIME);
        if (patch.neutralForKiller() != null) out.add(FIELD_NEUTRAL_FOR_KILLER);
        if (patch.neutralForInnocent() != null) out.add(FIELD_NEUTRAL_FOR_INNOCENT);
        if (patch.mafiaTeam() != null) out.add(FIELD_MAFIA_TEAM);
        if (patch.canUseInstinct() != null) out.add(FIELD_CAN_USE_INSTINCT);
        if (patch.instinctNightVision() != null) out.add(FIELD_INSTINCT_NIGHT_VISION);
        if (patch.canSeeTeammateKiller() != null) out.add(FIELD_CAN_SEE_TEAMMATE_KILLER);
        if (patch.otherModeRole() != null) out.add(FIELD_OTHER_MODE_ROLE);
        if (patch.hiddenForRotation() != null) out.add(FIELD_HIDDEN_FOR_ROTATION);
        if (patch.occupiedRoleCount() != null) out.add(FIELD_OCCUPIED_ROLE_COUNT);
        if (patch.specialMapRole() != null) out.add(FIELD_SPECIAL_MAP_ROLE);
        if (patch.occupation() != null) out.add(FIELD_OCCUPATION);
        if (patch.opposing() != null) out.add(FIELD_OPPOSING);
        if (patch.related() != null) out.add(FIELD_RELATED);
        return Collections.unmodifiableSet(out);
    }

    private static FoldedState fold(SRERole base, List<RolePatch> patches,
                                    @Nullable RoleBaseline baseline,
                                    @Nullable Function<RolePatch, Set<String>> fieldMask) {
        int color = baseline != null ? baseline.color() : base.getColor();
        MoodType mood = baseline != null ? baseline.mood() : base.getMoodType();
        boolean innocent = baseline != null ? baseline.innocent() : base.isInnocent();
        boolean canUseKiller = baseline != null ? baseline.canUseKiller() : base.canUseKiller();
        boolean neutral = baseline != null ? baseline.neutral() : base.isNeutrals();
        boolean vigilanteTeam = baseline != null ? baseline.vigilanteTeam() : base.isVigilanteTeam();
        int defaultMax = baseline != null ? baseline.defaultMaxCount() : base.defaultMaxCount;
        int enableChance = baseline != null ? baseline.defaultEnableChance() : base.defaultEnableChance;
        int needPlayerCount = baseline != null ? baseline.defaultEnableNeedPlayerCount() : base.defaultEnableNeedPlayerCount;
        int maxPlayerCount = baseline != null ? baseline.defaultEnableMaxPlayerCount() : base.defaultEnableMaxPlayerCount;
        boolean canSeeCoin = baseline != null ? baseline.canSeeCoin() : base.canSeeCoin();
        boolean canPickUpRevolver = baseline != null ? baseline.canPickUpRevolver() : base.canPickUpRevolver();
        boolean canBeRandomed = baseline != null ? baseline.canBeRandomed() : base.canBeRandomedDefination();
        int maxSprintTime = baseline != null ? baseline.maxSprintTime() : base.getMaxSprintTime();
        boolean canSeeTime = baseline != null ? baseline.canSeeTime() : base.canSeeTime();
        boolean neutralForKiller = baseline != null ? baseline.neutralForKiller() : base.isNeutralForKiller();
        boolean neutralForInnocent = baseline != null ? baseline.neutralForInnocent() : base.isNeutralForInnocent();
        boolean mafiaTeam = baseline != null ? baseline.mafiaTeam() : base.isMafiaTeam();
        boolean canUseInstinct = baseline != null ? baseline.canUseInstinct() : base.canUseInstinct();
        boolean instinctNightVision = baseline != null ? baseline.instinctNightVision() : base.haveInstinctNightVision();
        boolean canSeeTeammateKiller = baseline != null ? baseline.canSeeTeammateKiller() : base.canSeeTeammateKillerRole();
        boolean otherModeRole = baseline != null ? baseline.otherModeRole() : base.isOtherModeRole();
        boolean hiddenForRotation = baseline != null ? baseline.hiddenForRotation()
                : base.getFlags().contains("inner.role_rotation.hidden");
        int occupiedRoleCount = baseline != null ? baseline.occupiedRoleCount() : base.getOccupiedRoleCount();
        SpecialMapRoleMap specialMapRole = baseline != null ? baseline.specialMapRole() : base.getSpecialMapRole();
        List<RoleKey> occupation = baseline != null ? baseline.occupationKeys()
                : seedRelationKeys(base, RelationKind.OCCUPATION);
        List<RoleKey> opposing = baseline != null ? baseline.opposingKeys()
                : seedRelationKeys(base, RelationKind.OPPOSING);
        List<RoleKey> related = baseline != null ? baseline.relatedKeys()
                : seedRelationKeys(base, RelationKind.RELATED);
        List<RoleSkillSpec> skills = baseline != null ? baseline.skillSpecs() : seedSkills(base);
        boolean skillsSpecified = false;

        for (RolePatch patch : patches) {
            Set<String> masked = fieldMask == null ? null : fieldMask.apply(patch);
            if (patch.color() != null && allows(masked, FIELD_COLOR)) color = patch.color().color();
            if (patch.mood() != null && allows(masked, FIELD_MOOD)) mood = patch.mood().mood();
            if (patch.innocent() != null && allows(masked, FIELD_INNOCENT)) {
                innocent = applyBoolean(patch.innocent(), innocent);
            }
            if (patch.canUseKiller() != null && allows(masked, FIELD_CAN_USE_KILLER)) {
                canUseKiller = applyBoolean(patch.canUseKiller(), canUseKiller);
            }
            if (patch.neutral() != null && allows(masked, FIELD_NEUTRAL)) {
                neutral = applyBoolean(patch.neutral(), neutral);
            }
            if (patch.vigilanteTeam() != null && allows(masked, FIELD_VIGILANTE_TEAM)) {
                vigilanteTeam = applyBoolean(patch.vigilanteTeam(), vigilanteTeam);
            }
            if (patch.defaultMax() != null && allows(masked, FIELD_DEFAULT_MAX)) {
                defaultMax = applyNumeric(patch.defaultMax(), defaultMax);
            }
            if (patch.enableChance() != null && allows(masked, FIELD_ENABLE_CHANCE)) {
                enableChance = applyNumeric(patch.enableChance(), enableChance);
            }
            if (patch.needPlayerCount() != null && allows(masked, FIELD_NEED_PLAYER_COUNT)) {
                needPlayerCount = applyNumeric(patch.needPlayerCount(), needPlayerCount);
            }
            if (patch.maxPlayerCount() != null && allows(masked, FIELD_MAX_PLAYER_COUNT)) {
                maxPlayerCount = applyNumeric(patch.maxPlayerCount(), maxPlayerCount);
            }
            if (patch.canSeeCoin() != null && allows(masked, FIELD_CAN_SEE_COIN)) {
                canSeeCoin = applyBoolean(patch.canSeeCoin(), canSeeCoin);
            }
            if (patch.canPickUpRevolver() != null && allows(masked, FIELD_CAN_PICKUP_REVOLVER)) {
                canPickUpRevolver = applyBoolean(patch.canPickUpRevolver(), canPickUpRevolver);
            }
            if (patch.canBeRandomed() != null && allows(masked, FIELD_CAN_BE_RANDOMED)) {
                canBeRandomed = applyBoolean(patch.canBeRandomed(), canBeRandomed);
            }
            if (patch.maxSprintTime() != null && allows(masked, FIELD_MAX_SPRINT_TIME)) {
                maxSprintTime = applyNumeric(patch.maxSprintTime(), maxSprintTime);
            }
            if (patch.canSeeTime() != null && allows(masked, FIELD_CAN_SEE_TIME)) {
                canSeeTime = applyBoolean(patch.canSeeTime(), canSeeTime);
            }
            if (patch.neutralForKiller() != null && allows(masked, FIELD_NEUTRAL_FOR_KILLER)) {
                neutralForKiller = applyBoolean(patch.neutralForKiller(), neutralForKiller);
            }
            if (patch.neutralForInnocent() != null && allows(masked, FIELD_NEUTRAL_FOR_INNOCENT)) {
                neutralForInnocent = applyBoolean(patch.neutralForInnocent(), neutralForInnocent);
            }
            if (patch.mafiaTeam() != null && allows(masked, FIELD_MAFIA_TEAM)) {
                mafiaTeam = applyBoolean(patch.mafiaTeam(), mafiaTeam);
            }
            if (patch.canUseInstinct() != null && allows(masked, FIELD_CAN_USE_INSTINCT)) {
                canUseInstinct = applyBoolean(patch.canUseInstinct(), canUseInstinct);
            }
            if (patch.instinctNightVision() != null && allows(masked, FIELD_INSTINCT_NIGHT_VISION)) {
                instinctNightVision = applyBoolean(patch.instinctNightVision(), instinctNightVision);
            }
            if (patch.canSeeTeammateKiller() != null && allows(masked, FIELD_CAN_SEE_TEAMMATE_KILLER)) {
                canSeeTeammateKiller = applyBoolean(patch.canSeeTeammateKiller(), canSeeTeammateKiller);
            }
            if (patch.otherModeRole() != null && allows(masked, FIELD_OTHER_MODE_ROLE)) {
                otherModeRole = applyBoolean(patch.otherModeRole(), otherModeRole);
            }
            if (patch.hiddenForRotation() != null && allows(masked, FIELD_HIDDEN_FOR_ROTATION)) {
                hiddenForRotation = applyBoolean(patch.hiddenForRotation(), hiddenForRotation);
            }
            if (patch.occupiedRoleCount() != null && allows(masked, FIELD_OCCUPIED_ROLE_COUNT)) {
                occupiedRoleCount = applyNumeric(patch.occupiedRoleCount(), occupiedRoleCount);
            }
            if (patch.specialMapRole() != null && allows(masked, FIELD_SPECIAL_MAP_ROLE)) {
                specialMapRole = patch.specialMapRole().map();
            }
            if (patch.occupation() != null && allows(masked, FIELD_OCCUPATION)) {
                occupation = applyList(patch.occupation(), occupation);
            }
            if (patch.opposing() != null && allows(masked, FIELD_OPPOSING)) {
                opposing = applyList(patch.opposing(), opposing);
            }
            if (patch.related() != null && allows(masked, FIELD_RELATED)) {
                related = applyList(patch.related(), related);
            }
            if (patch.skills() != null) {
                skills = patch.skills().apply(skills);
                skillsSpecified = true;
            }
        }

        return new FoldedState(
                color, mood, innocent, canUseKiller, neutral, vigilanteTeam,
                defaultMax, enableChance, needPlayerCount, maxPlayerCount,
                canSeeCoin, canPickUpRevolver, canBeRandomed, maxSprintTime, canSeeTime,
                neutralForKiller, neutralForInnocent, mafiaTeam,
                canUseInstinct, instinctNightVision, canSeeTeammateKiller,
                otherModeRole, hiddenForRotation, occupiedRoleCount, specialMapRole,
                occupation, opposing, related, skills, skillsSpecified);
    }

    private static boolean allows(@Nullable Set<String> masked, String field) {
        return masked == null || !masked.contains(field);
    }

    private record FoldedState(
            int color, MoodType mood, boolean innocent, boolean canUseKiller, boolean neutral,
            boolean vigilanteTeam, int defaultMax, int enableChance, int needPlayerCount,
            int maxPlayerCount, boolean canSeeCoin, boolean canPickUpRevolver, boolean canBeRandomed,
            int maxSprintTime, boolean canSeeTime, boolean neutralForKiller, boolean neutralForInnocent,
            boolean mafiaTeam, boolean canUseInstinct, boolean instinctNightVision,
            boolean canSeeTeammateKiller, boolean otherModeRole, boolean hiddenForRotation,
            int occupiedRoleCount, SpecialMapRoleMap specialMapRole,
            List<RoleKey> occupation, List<RoleKey> opposing, List<RoleKey> related,
            List<RoleSkillSpec> skills, boolean skillsSpecified) {}

    /** Compiles a replacement definition into a {@link ManagedSRERole}. */
    public static ManagedSRERole compileReplacement(RoleReplacement replacement) {
        return ManagedSRERole.from(replacement.replacement());
    }

    /**
     * Folds a {@link RolePatch.RoleKeyListPatch} onto a running key list.
     * {@code null} patch leaves {@code current} unchanged.
     */
    public static List<RoleKey> applyList(@Nullable RolePatch.RoleKeyListPatch patch, List<RoleKey> current) {
        if (patch == null) {
            return current;
        }
        return switch (patch.op()) {
            case APPEND -> {
                List<RoleKey> next = new ArrayList<>(current);
                next.addAll(patch.keys());
                yield List.copyOf(next);
            }
            case REMOVE -> current.stream().filter(k -> !patch.keys().contains(k)).toList();
            case REPLACE_ALL -> List.copyOf(patch.keys());
        };
    }

    /**
     * Resolves {@code rel}'s {@link RoleKey}s through {@code resolve} and applies
     * the matching upstream relation setters. Unresolved keys are skipped.
     */
    public static void linkRelations(SRERole self, RoleRelationProfile rel,
                                     Function<RoleKey, SRERole> resolve) {
        if (self == null || rel == null || resolve == null) {
            return;
        }
        applyResolved(self, rel.occupation(), resolve, SRERole::addOccupationRoleOnce);
        if (rel.opposingTwoWay()) {
            applyResolved(self, rel.opposing(), resolve, SRERole::addTwoWayOpposingRole);
        } else {
            applyResolved(self, rel.opposing(), resolve, SRERole::addOpposingRole);
        }
        applyResolved(self, rel.related(), resolve, SRERole::addBothRelatedRole);
    }

    private static void applyResolved(SRERole self, List<RoleKey> keys,
                                      Function<RoleKey, SRERole> resolve,
                                      BiConsumer<SRERole, SRERole> apply) {
        for (RoleKey key : keys) {
            SRERole other = resolve.apply(key);
            if (other != null) {
                apply.accept(self, other);
            }
        }
    }

    private enum RelationKind { OCCUPATION, OPPOSING, RELATED }

    private static List<RoleKey> seedRelationKeys(SRERole base, RelationKind kind) {
        if (base instanceof ManagedSRERole managed) {
            return switch (kind) {
                case OCCUPATION -> managed.occupationRoleKeys();
                case OPPOSING -> managed.opposingRoleKeys();
                case RELATED -> managed.relatedRoleKeys();
            };
        }
        return List.of();
    }

    private static List<RoleSkillSpec> seedSkills(SRERole base) {
        if (base instanceof ManagedSRERole managed) {
            return managed.skills();
        }
        return List.of();
    }

    private static boolean applyBoolean(RolePatch.BooleanPatch p, boolean current) {
        return switch (p.op()) {
            case SET -> p.value();
            case AND -> current && p.value();
            case OR -> current || p.value();
        };
    }

    private static int applyNumeric(RolePatch.IntPatch p, int current) {
        return switch (p.op()) {
            case SET -> p.value();
            case ADD -> current + p.value();
            case MIN -> Math.min(current, p.value());
            case MAX -> Math.max(current, p.value());
        };
    }
}
