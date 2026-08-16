package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.PatchPriority;

/**
 * Unified internal entry shell for both v2 declarations and v1 declarations
 * translated by the legacy bridge (fix-doc §4.2). {@code entryId} is core-owned
 * and stable; status is a compile result.
 *
 * @param <T> the declaration payload (a v2 {@code RolePatch}/{@code RoleReplacement}/
 *            {@code RoleAlias}, or the v1 definition / a {@link RolePatchBundle})
 */
public record ManagedRoleEntry<T>(
        String entryId,
        String providerId,
        String entryKey,
        RoleOperation operation,
        RoleKey target,
        PatchPriority priority,
        T declaration,
        EntryStatus status,
        String statusMessage,
        boolean legacy) {

    /** A copy of this entry with a different compile status and message. */
    public ManagedRoleEntry<T> withStatus(EntryStatus status, String statusMessage) {
        return new ManagedRoleEntry<>(
                entryId, providerId, entryKey, operation, target, priority,
                declaration, status, statusMessage, legacy);
    }
}
