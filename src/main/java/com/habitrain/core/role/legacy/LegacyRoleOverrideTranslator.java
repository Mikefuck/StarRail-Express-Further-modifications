package com.habitrain.core.role.legacy;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleOperation;
import com.habitrain.core.role.extension.RolePatchBundle;
import com.habitrain.core.role.override.RoleOverrideRegistry;
import net.minecraft.resources.ResourceLocation;

/**
 * First-version bridge from the legacy {@code RoleOverrideApi} definitions into
 * the unified {@link ManagedRoleEntry} model (fix-doc §15.1), so v1 and v2
 * declarations can share one conflict computation and diagnostic view.
 *
 * <p>Runtime semantics of the v1 engine are untouched; the full v1-to-v2 runtime
 * migration is Phase G.
 */
public final class LegacyRoleOverrideTranslator {

    private LegacyRoleOverrideTranslator() {}

    /** Translates a v1 {@code REPLACE} definition into a unified entry shell. */
    public static ManagedRoleEntry<ReplaceRoleDefinition> translateReplace(ReplaceRoleDefinition def) {
        ResourceLocation replId = def.replacementRole().identifier();
        return new ManagedRoleEntry<>(
                RoleOverrideRegistry.entryId(def),
                def.sourceModId(),
                def.entryKey().orElse(replId == null ? "" : replId.getPath()),
                RoleOperation.REPLACE,
                RoleKey.of(def.targetRoleId()),
                PatchPriority.NORMAL,
                def,
                EntryStatus.LEGACY_UNMANAGED,
                "translated v1 REPLACE",
                true);
    }

    /** Translates a v1 {@code MODIFY} definition into a unified entry shell. */
    public static ManagedRoleEntry<RolePatchBundle> translateModify(ModifyRoleDefinition def) {
        boolean rawRegistrar = def.skillRegistrar().isPresent();
        EntryStatus status = rawRegistrar ? EntryStatus.INVALID : EntryStatus.LEGACY_UNMANAGED;
        String message = rawRegistrar
                ? "Legacy skillRegistrar is not reversible; use managedSkillPatch"
                : "translated v1 MODIFY";
        RolePatchBundle bundle = new RolePatchBundle(
                RoleKey.of(def.targetRoleId()),
                def.sourceModId(),
                rawRegistrar,
                def.roleBookAppendices());
        return new ManagedRoleEntry<>(
                RoleOverrideRegistry.entryId(def),
                def.sourceModId(),
                def.entryKey().orElse(def.targetRoleId().getPath()),
                RoleOperation.MODIFY,
                RoleKey.of(def.targetRoleId()),
                PatchPriority.NORMAL,
                bundle,
                status,
                message,
                true);
    }
}
