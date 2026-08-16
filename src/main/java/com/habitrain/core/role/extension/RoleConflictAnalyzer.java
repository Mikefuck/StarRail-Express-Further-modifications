package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.legacy.LegacyRoleOverrideTranslator;
import com.habitrain.core.role.override.RoleOverrideRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes the unified v1+v2 entry view and the cross-version same-target
 * conflicts (fix-doc §6.5). Runs once at freeze; the result is the diagnostic
 * baseline for config, commands and Mod Menu.
 *
 * <p>Conflict rule mirrors the v1 engine's existing runtime guard: any legacy
 * (v1) {@code REPLACE}/{@code MODIFY} whose target is owned by a v2
 * {@code REPLACE}/{@code MODIFY} is {@code CONFLICT} and excluded from the
 * effective snapshot.
 *
 * <p>v2 entries are also checked at field granularity. Two enabled
 * {@code MODIFY} entries that issue incompatible scalar {@code SET}s for the
 * same target, priority and field are a conflict unless the server operator
 * selected one of those entries as the field winner. Every conflicting entry is
 * isolated; no arbitrary registration-order winner is chosen.
 */
public final class RoleConflictAnalyzer {

    private RoleConflictAnalyzer() {}

    /** The unified entry view with statuses, v2 first then translated v1. */
    public static List<ManagedRoleEntry<?>> analyze() {
        return analyze(RoleOverrideRegistry.INSTANCE.getReplaces(),
                RoleOverrideRegistry.INSTANCE.getModifies());
    }

    /**
     * {@link #analyze()} against explicit v1 collections (unit-test friendly:
     * the v1 registry is a frozen singleton with no public reset).
     */
    public static List<ManagedRoleEntry<?>> analyze(
            List<ReplaceRoleDefinition> v1Replaces,
            List<ModifyRoleDefinition> v1Modifies) {
        List<ManagedRoleEntry<?>> entries = new ArrayList<>();
        entries.addAll(RoleExtensionRegistry.INSTANCE.v2Entries());

        Set<String> v2Conflicts = conflictingV2ModifyEntryIds(entries);
        for (int i = 0; i < entries.size(); i++) {
            ManagedRoleEntry<?> entry = entries.get(i);
            if (v2Conflicts.contains(entry.entryId())) {
                entries.set(i, entry.withStatus(EntryStatus.CONFLICT,
                        "v2 MODIFY conflicts with another same-priority scalar SET on "
                                + entry.target() + "; configure a conflict winner"));
            }
        }

        for (ReplaceRoleDefinition def : v1Replaces) {
            entries.add(LegacyRoleOverrideTranslator.translateReplace(def));
        }
        for (ModifyRoleDefinition def : v1Modifies) {
            entries.add(LegacyRoleOverrideTranslator.translateModify(def));
        }

        for (int i = 0; i < entries.size(); i++) {
            ManagedRoleEntry<?> entry = entries.get(i);
            if (!entry.legacy()) {
                continue;
            }
            // Only a config-ENABLED v2 owner suppresses the v1 entry; a disabled
            // v2 REPLACE/MODIFY leaves the target to the legacy engine.
            boolean v2Owns = RoleExtensionRegistry.INSTANCE.isActiveReplaced(entry.target().location())
                    || RoleExtensionRegistry.INSTANCE.isActiveModified(entry.target().location());
            if (v2Owns) {
                entries.set(i, entry.withStatus(EntryStatus.CONFLICT,
                        "v1 override conflicts with a v2 REPLACE/MODIFY on " + entry.target()));
            }
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * Returns enabled v2 {@code MODIFY} entry ids that must be excluded because
     * they participate in an unresolved same-priority scalar {@code SET}
     * conflict. This intentionally does not call {@link #analyze()}, allowing
     * the runtime patch filter to use exactly the same rule without recursively
     * rebuilding the diagnostic view.
     */
    public static Set<String> conflictingV2ModifyEntryIds() {
        return conflictingV2ModifyEntryIds(RoleExtensionRegistry.INSTANCE.v2Entries());
    }

    private static Set<String> conflictingV2ModifyEntryIds(List<ManagedRoleEntry<?>> entries) {
        Map<ScalarFieldKey, List<ScalarWrite>> writesByField = new LinkedHashMap<>();
        for (ManagedRoleEntry<?> entry : entries) {
            if (entry.legacy()
                    || entry.operation() != RoleOperation.MODIFY
                    || entry.status() != EntryStatus.ACTIVE
                    || !(entry.declaration() instanceof RolePatch patch)) {
                continue;
            }
            for (var write : scalarSetWrites(patch).entrySet()) {
                ScalarFieldKey key = new ScalarFieldKey(
                        patch.target().location(), entry.priority().value(), write.getKey());
                writesByField.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new ScalarWrite(entry.entryId(), write.getValue()));
            }
        }

        Set<String> conflicting = new HashSet<>();
        for (var grouped : writesByField.entrySet()) {
            List<ScalarWrite> writes = grouped.getValue();
            if (writes.size() < 2 || allSameValue(writes)) {
                continue;
            }

            String winner = RoleExtensionConfigService.INSTANCE.winnerFor(
                    grouped.getKey().target(), grouped.getKey().field());
            if (winner != null && writes.stream().anyMatch(write -> winner.equals(write.entryId()))) {
                // A real administrator choice is a valid resolution. The
                // compiler will mask the non-winner writes field by field.
                continue;
            }
            for (ScalarWrite write : writes) {
                conflicting.add(write.entryId());
            }
        }
        return Set.copyOf(conflicting);
    }

    private static boolean allSameValue(List<ScalarWrite> writes) {
        Object first = writes.getFirst().value();
        for (int i = 1; i < writes.size(); i++) {
            if (!Objects.equals(first, writes.get(i).value())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts only scalar, explicit {@code SET} writes. Composing operations
     * ({@code ADD}/{@code MIN}/{@code MAX}/{@code AND}/{@code OR}) are ordered
     * operations by design and therefore are not conflict candidates.
     */
    private static Map<String, Object> scalarSetWrites(RolePatch patch) {
        Map<String, Object> writes = new LinkedHashMap<>();
        if (patch.color() != null) writes.put(RoleExtensionCompiler.FIELD_COLOR, patch.color().color());
        if (patch.mood() != null) writes.put(RoleExtensionCompiler.FIELD_MOOD, patch.mood().mood());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_INNOCENT, patch.innocent());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_CAN_USE_KILLER, patch.canUseKiller());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_NEUTRAL, patch.neutral());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_VIGILANTE_TEAM, patch.vigilanteTeam());
        putIntSet(writes, RoleExtensionCompiler.FIELD_DEFAULT_MAX, patch.defaultMax());
        putIntSet(writes, RoleExtensionCompiler.FIELD_ENABLE_CHANCE, patch.enableChance());
        putIntSet(writes, RoleExtensionCompiler.FIELD_NEED_PLAYER_COUNT, patch.needPlayerCount());
        putIntSet(writes, RoleExtensionCompiler.FIELD_MAX_PLAYER_COUNT, patch.maxPlayerCount());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_CAN_SEE_COIN, patch.canSeeCoin());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_CAN_PICKUP_REVOLVER, patch.canPickUpRevolver());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_CAN_BE_RANDOMED, patch.canBeRandomed());
        putIntSet(writes, RoleExtensionCompiler.FIELD_MAX_SPRINT_TIME, patch.maxSprintTime());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_CAN_SEE_TIME, patch.canSeeTime());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_NEUTRAL_FOR_KILLER, patch.neutralForKiller());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_NEUTRAL_FOR_INNOCENT, patch.neutralForInnocent());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_MAFIA_TEAM, patch.mafiaTeam());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_CAN_USE_INSTINCT, patch.canUseInstinct());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_INSTINCT_NIGHT_VISION, patch.instinctNightVision());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_CAN_SEE_TEAMMATE_KILLER,
                patch.canSeeTeammateKiller());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_OTHER_MODE_ROLE, patch.otherModeRole());
        putBooleanSet(writes, RoleExtensionCompiler.FIELD_HIDDEN_FOR_ROTATION, patch.hiddenForRotation());
        putIntSet(writes, RoleExtensionCompiler.FIELD_OCCUPIED_ROLE_COUNT, patch.occupiedRoleCount());
        if (patch.specialMapRole() != null) {
            writes.put(RoleExtensionCompiler.FIELD_SPECIAL_MAP_ROLE, patch.specialMapRole().map());
        }
        return writes;
    }

    private static void putBooleanSet(Map<String, Object> writes, String field,
                                      RolePatch.BooleanPatch patch) {
        if (patch != null && patch.op() == RolePatch.BooleanOp.SET) {
            writes.put(field, patch.value());
        }
    }

    private static void putIntSet(Map<String, Object> writes, String field,
                                  RolePatch.IntPatch patch) {
        if (patch != null && patch.op() == RolePatch.NumericOp.SET) {
            writes.put(field, patch.value());
        }
    }

    private record ScalarFieldKey(ResourceLocation target, int priority, String field) {}

    private record ScalarWrite(String entryId, Object value) {}
}
