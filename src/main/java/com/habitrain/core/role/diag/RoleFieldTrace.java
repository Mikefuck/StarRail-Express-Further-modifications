package com.habitrain.core.role.diag;

import com.habitrain.core.api.role.v2.DiagnosticStatus;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.ConfiguredPatch;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleBaseline;
import com.habitrain.core.role.extension.RoleBaselineStore;
import com.habitrain.core.role.extension.RoleExtensionCompiler;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-field value trace (fix-doc §13.3):
 *
 * <pre>{@code
 * baseline: 1
 * [EARLY] provider_a$entry SET 2
 * [NORMAL] provider_b$entry MAX 3
 * final: 3
 * status: ACTIVE
 * snapshot: role-snapshot-v42
 * }</pre>
 *
 * <p>The baseline reads the pristine spawn fields from {@link RoleBaselineStore}
 * when captured and falls back to the role's current value otherwise; the final
 * value comes from folding the config-enabled patches (conflict winners applied)
 * so the trace matches what the effective snapshot actually shows.
 */
public final class RoleFieldTrace {

    private RoleFieldTrace() {}

    /** Renders {@code trace <role> <field>} for one role+field. */
    public static List<String> trace(RoleKey key, String field) {
        List<String> lines = new ArrayList<>();
        RoleSnapshot snap = RoleSnapshotManager.INSTANCE.current();
        SRERole base = null;
        if (snap != null && snap.find(key).isPresent()) {
            base = snap.find(key).get().role();
        }
        if (base == null || base.identifier() == null) {
            lines.add("  role not found in current snapshot");
            return lines;
        }
        ResourceLocation id = base.identifier();
        List<ConfiguredPatch> configured = RoleExtensionRegistry.INSTANCE.configuredPatchesFor(id);

        lines.add("baseline: " + baseline(base, field));
        boolean any = false;
        for (ConfiguredPatch cp : configured) {
            if (!RoleExtensionCompiler.fieldsSetBy(cp.patch()).contains(field)) {
                continue;
            }
            any = true;
            lines.add("[" + priorityLabel(cp.patch()) + "] " + cp.entryId() + " " + patchOp(cp.patch(), field));
        }
        if (!any) {
            lines.add("  (no enabled patches set " + field + ")");
        }
        RoleBaseline bl = RoleBaselineStore.baseline(base);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlayConfigured(base, configured, bl);
        lines.add("final: " + (overlay == null ? baseline(base, field) : finalValue(overlay, field)));
        lines.add("status: " + statusLabel(id));
        lines.add("snapshot: " + (snap == null ? "none" : snap.id().toString()));
        return lines;
    }

    // ------------------------------------------------------------------
    // baseline / final / patch rendering
    // ------------------------------------------------------------------

    private static String baseline(SRERole role, String field) {
        RoleBaseline bl = RoleBaselineStore.baseline(role);
        return switch (field) {
            case RoleExtensionCompiler.FIELD_COLOR -> String.valueOf(bl != null ? bl.color() : role.getColor());
            case RoleExtensionCompiler.FIELD_MOOD -> String.valueOf(bl != null ? bl.mood() : role.getMoodType());
            case RoleExtensionCompiler.FIELD_INNOCENT -> String.valueOf(bl != null ? bl.innocent() : role.isInnocent());
            case RoleExtensionCompiler.FIELD_CAN_USE_KILLER -> String.valueOf(bl != null ? bl.canUseKiller() : role.canUseKiller());
            case RoleExtensionCompiler.FIELD_NEUTRAL -> String.valueOf(bl != null ? bl.neutral() : role.isNeutrals());
            case RoleExtensionCompiler.FIELD_VIGILANTE_TEAM -> String.valueOf(bl != null ? bl.vigilanteTeam() : role.isVigilanteTeam());
            case RoleExtensionCompiler.FIELD_DEFAULT_MAX -> String.valueOf(bl != null ? bl.defaultMaxCount() : role.defaultMaxCount);
            case RoleExtensionCompiler.FIELD_ENABLE_CHANCE -> String.valueOf(bl != null ? bl.defaultEnableChance() : role.defaultEnableChance);
            case RoleExtensionCompiler.FIELD_NEED_PLAYER_COUNT -> String.valueOf(bl != null ? bl.defaultEnableNeedPlayerCount() : role.defaultEnableNeedPlayerCount);
            case RoleExtensionCompiler.FIELD_MAX_PLAYER_COUNT -> String.valueOf(bl != null ? bl.defaultEnableMaxPlayerCount() : role.defaultEnableMaxPlayerCount);
            case RoleExtensionCompiler.FIELD_CAN_SEE_COIN -> String.valueOf(bl != null ? bl.canSeeCoin() : role.canSeeCoin());
            case RoleExtensionCompiler.FIELD_CAN_PICKUP_REVOLVER -> String.valueOf(bl != null ? bl.canPickUpRevolver() : role.canPickUpRevolver());
            case RoleExtensionCompiler.FIELD_CAN_BE_RANDOMED -> String.valueOf(bl != null ? bl.canBeRandomed() : role.canBeRandomedDefination());
            case RoleExtensionCompiler.FIELD_MAX_SPRINT_TIME -> String.valueOf(bl != null ? bl.maxSprintTime() : role.getMaxSprintTime());
            case RoleExtensionCompiler.FIELD_CAN_SEE_TIME -> String.valueOf(bl != null ? bl.canSeeTime() : role.canSeeTime());
            case RoleExtensionCompiler.FIELD_NEUTRAL_FOR_KILLER -> String.valueOf(bl != null ? bl.neutralForKiller() : role.isNeutralForKiller());
            case RoleExtensionCompiler.FIELD_NEUTRAL_FOR_INNOCENT -> String.valueOf(bl != null ? bl.neutralForInnocent() : role.isNeutralForInnocent());
            case RoleExtensionCompiler.FIELD_MAFIA_TEAM -> String.valueOf(bl != null ? bl.mafiaTeam() : role.isMafiaTeam());
            case RoleExtensionCompiler.FIELD_CAN_USE_INSTINCT -> String.valueOf(bl != null ? bl.canUseInstinct() : role.canUseInstinct());
            case RoleExtensionCompiler.FIELD_INSTINCT_NIGHT_VISION -> String.valueOf(bl != null ? bl.instinctNightVision() : role.haveInstinctNightVision());
            case RoleExtensionCompiler.FIELD_CAN_SEE_TEAMMATE_KILLER -> String.valueOf(bl != null ? bl.canSeeTeammateKiller() : role.canSeeTeammateKillerRole());
            case RoleExtensionCompiler.FIELD_OTHER_MODE_ROLE -> String.valueOf(bl != null ? bl.otherModeRole() : role.isOtherModeRole());
            case RoleExtensionCompiler.FIELD_HIDDEN_FOR_ROTATION -> String.valueOf(bl != null ? bl.flags().contains("inner.role_rotation.hidden") : role.getFlags().contains("inner.role_rotation.hidden"));
            case RoleExtensionCompiler.FIELD_OCCUPIED_ROLE_COUNT -> String.valueOf(bl != null ? bl.occupiedRoleCount() : role.getOccupiedRoleCount());
            case RoleExtensionCompiler.FIELD_SPECIAL_MAP_ROLE -> String.valueOf(bl != null ? bl.specialMapRole() : role.getSpecialMapRole());
            case RoleExtensionCompiler.FIELD_OCCUPATION -> String.valueOf(bl != null ? bl.occupationRoles().size() : role.occupationRoles.size());
            case RoleExtensionCompiler.FIELD_OPPOSING -> String.valueOf(bl != null ? bl.opposingRoles().size() : role.opposingRoles.size());
            case RoleExtensionCompiler.FIELD_RELATED -> String.valueOf(bl != null ? bl.relatedRoles().size() : role.relatedRoles.size());
            case RoleExtensionCompiler.FIELD_SKILLS -> String.valueOf(bl != null ? bl.skills().size()
                    : (role instanceof com.habitrain.core.role.extension.ManagedSRERole managed ? managed.skills().size() : 0));
            default -> "?";
        };
    }

    private static String finalValue(CompiledModifyOverlay o, String field) {
        return switch (field) {
            case RoleExtensionCompiler.FIELD_COLOR -> String.valueOf(o.color());
            case RoleExtensionCompiler.FIELD_MOOD -> String.valueOf(o.mood());
            case RoleExtensionCompiler.FIELD_INNOCENT -> String.valueOf(o.innocent());
            case RoleExtensionCompiler.FIELD_CAN_USE_KILLER -> String.valueOf(o.canUseKiller());
            case RoleExtensionCompiler.FIELD_NEUTRAL -> String.valueOf(o.neutral());
            case RoleExtensionCompiler.FIELD_VIGILANTE_TEAM -> String.valueOf(o.vigilanteTeam());
            case RoleExtensionCompiler.FIELD_DEFAULT_MAX -> String.valueOf(o.defaultMax());
            case RoleExtensionCompiler.FIELD_ENABLE_CHANCE -> String.valueOf(o.enableChance());
            case RoleExtensionCompiler.FIELD_NEED_PLAYER_COUNT -> String.valueOf(o.needPlayerCount());
            case RoleExtensionCompiler.FIELD_MAX_PLAYER_COUNT -> String.valueOf(o.maxPlayerCount());
            case RoleExtensionCompiler.FIELD_CAN_SEE_COIN -> String.valueOf(o.canSeeCoin());
            case RoleExtensionCompiler.FIELD_CAN_PICKUP_REVOLVER -> String.valueOf(o.canPickUpRevolver());
            case RoleExtensionCompiler.FIELD_CAN_BE_RANDOMED -> String.valueOf(o.canBeRandomed());
            case RoleExtensionCompiler.FIELD_MAX_SPRINT_TIME -> String.valueOf(o.maxSprintTime());
            case RoleExtensionCompiler.FIELD_CAN_SEE_TIME -> String.valueOf(o.canSeeTime());
            case RoleExtensionCompiler.FIELD_NEUTRAL_FOR_KILLER -> String.valueOf(o.neutralForKiller());
            case RoleExtensionCompiler.FIELD_NEUTRAL_FOR_INNOCENT -> String.valueOf(o.neutralForInnocent());
            case RoleExtensionCompiler.FIELD_MAFIA_TEAM -> String.valueOf(o.mafiaTeam());
            case RoleExtensionCompiler.FIELD_CAN_USE_INSTINCT -> String.valueOf(o.canUseInstinct());
            case RoleExtensionCompiler.FIELD_INSTINCT_NIGHT_VISION -> String.valueOf(o.instinctNightVision());
            case RoleExtensionCompiler.FIELD_CAN_SEE_TEAMMATE_KILLER -> String.valueOf(o.canSeeTeammateKiller());
            case RoleExtensionCompiler.FIELD_OTHER_MODE_ROLE -> String.valueOf(o.otherModeRole());
            case RoleExtensionCompiler.FIELD_HIDDEN_FOR_ROTATION -> String.valueOf(o.hiddenForRotation());
            case RoleExtensionCompiler.FIELD_OCCUPIED_ROLE_COUNT -> String.valueOf(o.occupiedRoleCount());
            case RoleExtensionCompiler.FIELD_SPECIAL_MAP_ROLE -> String.valueOf(o.specialMapRole());
            case RoleExtensionCompiler.FIELD_OCCUPATION -> String.valueOf(o.occupationKeys().size());
            case RoleExtensionCompiler.FIELD_OPPOSING -> String.valueOf(o.opposingKeys().size());
            case RoleExtensionCompiler.FIELD_RELATED -> String.valueOf(o.relatedKeys().size());
            case RoleExtensionCompiler.FIELD_SKILLS -> String.valueOf(o.skills() == null ? 0 : o.skills().size());
            default -> "?";
        };
    }

    private static String patchOp(RolePatch patch, String field) {
        return switch (field) {
            case RoleExtensionCompiler.FIELD_COLOR ->
                    patch.color() != null ? "SET #" + Integer.toHexString(patch.color().color())
                            : (patch.colorProvider() != null ? "FN colorProvider" : "?");
            case RoleExtensionCompiler.FIELD_MOOD ->
                    patch.mood() == null ? "?" : "SET " + patch.mood().mood();
            case RoleExtensionCompiler.FIELD_INNOCENT -> renderBool(patch.innocent());
            case RoleExtensionCompiler.FIELD_CAN_USE_KILLER -> renderBool(patch.canUseKiller());
            case RoleExtensionCompiler.FIELD_NEUTRAL -> renderBool(patch.neutral());
            case RoleExtensionCompiler.FIELD_VIGILANTE_TEAM -> renderBool(patch.vigilanteTeam());
            case RoleExtensionCompiler.FIELD_DEFAULT_MAX ->
                    patch.defaultMax() != null ? renderInt(patch.defaultMax())
                            : (patch.spawnInfoPatch() != null ? "FN spawnInfoPatch" : "?");
            case RoleExtensionCompiler.FIELD_ENABLE_CHANCE ->
                    patch.enableChance() != null ? renderInt(patch.enableChance())
                            : (patch.spawnInfoPatch() != null ? "FN spawnInfoPatch" : "?");
            case RoleExtensionCompiler.FIELD_NEED_PLAYER_COUNT ->
                    patch.needPlayerCount() != null ? renderInt(patch.needPlayerCount())
                            : (patch.spawnInfoPatch() != null ? "FN spawnInfoPatch" : "?");
            case RoleExtensionCompiler.FIELD_MAX_PLAYER_COUNT ->
                    patch.maxPlayerCount() != null ? renderInt(patch.maxPlayerCount())
                            : (patch.spawnInfoPatch() != null ? "FN spawnInfoPatch" : "?");
            case RoleExtensionCompiler.FIELD_CAN_SEE_COIN -> renderBool(patch.canSeeCoin());
            case RoleExtensionCompiler.FIELD_CAN_PICKUP_REVOLVER -> renderBool(patch.canPickUpRevolver());
            case RoleExtensionCompiler.FIELD_CAN_BE_RANDOMED -> renderBool(patch.canBeRandomed());
            case RoleExtensionCompiler.FIELD_MAX_SPRINT_TIME -> renderInt(patch.maxSprintTime());
            case RoleExtensionCompiler.FIELD_CAN_SEE_TIME -> renderBool(patch.canSeeTime());
            case RoleExtensionCompiler.FIELD_NEUTRAL_FOR_KILLER -> renderBool(patch.neutralForKiller());
            case RoleExtensionCompiler.FIELD_NEUTRAL_FOR_INNOCENT -> renderBool(patch.neutralForInnocent());
            case RoleExtensionCompiler.FIELD_MAFIA_TEAM -> renderBool(patch.mafiaTeam());
            case RoleExtensionCompiler.FIELD_CAN_USE_INSTINCT -> renderBool(patch.canUseInstinct());
            case RoleExtensionCompiler.FIELD_INSTINCT_NIGHT_VISION -> renderBool(patch.instinctNightVision());
            case RoleExtensionCompiler.FIELD_CAN_SEE_TEAMMATE_KILLER -> renderBool(patch.canSeeTeammateKiller());
            case RoleExtensionCompiler.FIELD_OTHER_MODE_ROLE -> renderBool(patch.otherModeRole());
            case RoleExtensionCompiler.FIELD_HIDDEN_FOR_ROTATION ->
                    patch.hiddenForRotation() != null ? renderBool(patch.hiddenForRotation())
                            : (patch.flagsPatch() != null ? "FN flagsPatch" : "?");
            case RoleExtensionCompiler.FIELD_OCCUPIED_ROLE_COUNT -> renderInt(patch.occupiedRoleCount());
            case RoleExtensionCompiler.FIELD_SPECIAL_MAP_ROLE ->
                    patch.specialMapRole() == null ? "?" : "SET " + patch.specialMapRole().map();
            case RoleExtensionCompiler.FIELD_OCCUPATION -> renderList(patch.occupation());
            case RoleExtensionCompiler.FIELD_OPPOSING -> renderList(patch.opposing());
            case RoleExtensionCompiler.FIELD_RELATED -> renderList(patch.related());
            case RoleExtensionCompiler.FIELD_SKILLS ->
                    patch.skills() == null ? "?" : patch.skills().op() + " " + patch.skills().skills().size();
            default -> "?";
        };
    }

    private static String renderInt(RolePatch.IntPatch p) {
        return p == null ? "?" : p.op() + " " + p.value();
    }

    private static String renderBool(RolePatch.BooleanPatch p) {
        return p == null ? "?" : p.op() + " " + p.value();
    }

    private static String renderList(RolePatch.RoleKeyListPatch p) {
        if (p == null) {
            return "?";
        }
        StringBuilder sb = new StringBuilder(p.op().name());
        sb.append(' ').append(p.keys().size()).append(':');
        p.keys().forEach(k -> sb.append(' ').append(k));
        return sb.toString();
    }

    private static String priorityLabel(RolePatch patch) {
        return switch (patch.priority()) {
            case EARLY -> "EARLY";
            case NORMAL -> "NORMAL";
            case LATE -> "LATE";
        };
    }

    private static String statusLabel(ResourceLocation id) {
        DiagnosticStatus best = null;
        for (ManagedRoleEntry<?> entry : RoleExtensionRegistry.INSTANCE.getCompiledEntries()) {
            if (entry.target() == null || !entry.target().location().equals(id)) {
                continue;
            }
            DiagnosticStatus s = toDiagnosticStatus(entry.status());
            if (s == DiagnosticStatus.ACTIVE) {
                return "ACTIVE";
            }
            if (best == null) {
                best = s;
            }
        }
        return best == null ? "NONE" : best.name();
    }

    private static DiagnosticStatus toDiagnosticStatus(EntryStatus status) {
        return switch (status) {
            case ACTIVE -> DiagnosticStatus.ACTIVE;
            case INVALID -> DiagnosticStatus.INVALID;
            case CONFLICT -> DiagnosticStatus.CONFLICT;
            case DISABLED -> DiagnosticStatus.DISABLED;
        };
    }
}
