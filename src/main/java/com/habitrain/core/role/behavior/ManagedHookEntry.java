package com.habitrain.core.role.behavior;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.behavior.RoleScope;
import com.habitrain.core.api.role.v2.definition.PatchPriority;

import java.util.Comparator;
import java.util.Objects;

/**
 * One provider's registered hook slot for a single {@link HookType}. Unlike the
 * old per-role merged container, providers are never merged away: each provider's
 * callbacks stay as their own entry, so two providers hooking the same category
 * for the same role both execute in {@link #ORDER stable order}.
 *
 * <p>The {@code callback} is the category interface instance (e.g.
 * {@code RoleCombatHooks}); {@link #type()} tells the dispatcher which method to
 * invoke and which breaker slot to use.
 */
public record ManagedHookEntry(
        String providerId,
        String entryId,
        RoleKey role,
        RoleScope scope,
        PatchPriority priority,
        HookType type,
        Object callback) {

    public ManagedHookEntry {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(callback, "callback");
    }

    /**
     * Stable execution order for one {@code (role, hookType)} list: ascending
     * {@link PatchPriority#value()}, then provider mod id, then entry id.
     */
    public static final Comparator<ManagedHookEntry> ORDER =
            Comparator.comparingInt((ManagedHookEntry e) -> e.priority().value())
                    .thenComparing(ManagedHookEntry::providerId)
                    .thenComparing(ManagedHookEntry::entryId);
}
