package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.PatchPriority;

/** A registered v2 declaration and its compiled diagnostic status. */
public record ManagedRoleEntry<T>(
        String entryId,
        String providerId,
        String entryKey,
        RoleOperation operation,
        RoleKey target,
        PatchPriority priority,
        T declaration,
        EntryStatus status,
        String statusMessage) {

    public ManagedRoleEntry<T> withStatus(EntryStatus status, String statusMessage) {
        return new ManagedRoleEntry<>(
                entryId, providerId, entryKey, operation, target, priority,
                declaration, status, statusMessage);
    }
}
