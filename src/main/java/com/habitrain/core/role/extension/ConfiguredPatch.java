package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.definition.RolePatch;

/**
 * A v2 {@code MODIFY} patch paired with its core-owned entryId (fix-doc §4.2).
 *
 * <p>{@code RoleExtensionRegistry#configuredPatchesFor} filters the registry's
 * patches by the enabled config and returns them in application order paired with
 * the entryId the conflict-winner config references, so the overlay fold can
 * mask the loser's fields on a per-{@code target#field} basis.
 */
public record ConfiguredPatch(RolePatch patch, String entryId) {

    public ConfiguredPatch {
        if (patch == null) {
            throw new IllegalArgumentException("patch required");
        }
    }
}
