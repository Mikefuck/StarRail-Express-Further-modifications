package com.habitrain.core.role.legacy;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.api.role.patch.ManagedSkillPatch;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.skill.RoleSkillPatch;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleOperation;
import com.habitrain.core.role.extension.RolePatchBundle;
import com.habitrain.core.role.override.RoleOverrideRegistry;
import io.wifi.starrailexpress.api.RoleSkill;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Bridge from the legacy {@code RoleOverrideApi} definitions into the unified
 * {@link ManagedRoleEntry} model (fix-doc §15.1), so v1 and v2 declarations can
 * share one conflict computation and diagnostic view.
 *
 * <p>Since the v1-parity work, {@code MODIFY} translation also produces a real
 * v2 {@link RolePatch}. The v1 runtime engine remains untouched; the translated
 * patch is available for diagnostics, migration tooling and future unified
 * activation.
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
                def.roleBookAppendices(),
                rawRegistrar ? null : translatePatch(def));
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

    /** Converts a v1 MODIFY definition into the closest equivalent v2 {@link RolePatch}. */
    public static RolePatch translatePatch(ModifyRoleDefinition def) {
        RolePatch.Builder builder = RolePatch.builder(RoleKey.of(def.targetRoleId()));

        // Legacy displayName/description are themselves patches: the simple
        // builder fields translate to the same v2 name/description patch slots.
        builder.namePatch((original, server) -> def.displayName());
        if (def.description().isPresent()) {
            builder.descriptionPatch((original, baseline) -> def.description().orElse(baseline));
        }
        def.colorPatch().ifPresent(builder::colorProvider);
        def.namePatch().ifPresent(builder::namePatch);
        def.descriptionPatch().ifPresent(builder::descriptionPatch);
        def.simpleDescriptionPatch().ifPresent(builder::simpleDescriptionPatch);
        def.defaultItemsPatch().ifPresent(builder::defaultItemsPatch);
        def.shopPatch().ifPresent(builder::shopPatch);
        def.shopTransform().ifPresent(builder::shopTransform);
        def.winConditionHook().ifPresent(builder::winConditionHook);
        def.flagsPatch().ifPresent(builder::flagsPatch);
        def.spawnInfoPatch().ifPresent(builder::spawnInfoPatch);
        def.managedSkillPatch().ifPresent(patch -> {
            RoleSkillPatch translated = tryTranslateSkills(patch);
            if (translated != null) {
                builder.skills(translated);
            }
        });

        // The legacy skillRegistrar path is intentionally not translated: it is
        // un-reversible and v2 has no equivalent side-effecting registrar.
        return builder.build();
    }

    private static @org.jetbrains.annotations.Nullable RoleSkillPatch tryTranslateSkills(ManagedSkillPatch patch) {
        List<RoleSkillSpec> specs;
        try {
            specs = patch.getDefinitions(null).stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(def -> def.id() != null)
                    .map(RoleSkillSpec::of)
                    .toList();
        } catch (Throwable t) {
            // ManagedSkillPatch is a runtime function; without the live SRERole
            // object it may be unevaluable at declaration time. Diagnostics keep
            // the un-reversible-registrar warning and omit the skill translation.
            return null;
        }
        return switch (patch.mode()) {
            case APPEND -> RoleSkillPatch.append(specs.toArray(RoleSkillSpec[]::new));
            case REPLACE_ALL -> RoleSkillPatch.replaceAll(specs.toArray(RoleSkillSpec[]::new));
            case REPLACE_MATCHING_IDS ->
                    RoleSkillPatch.replaceMatchingIds(specs.toArray(RoleSkillSpec[]::new));
        };
    }
}
