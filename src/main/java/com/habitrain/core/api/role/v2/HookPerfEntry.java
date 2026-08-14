package com.habitrain.core.api.role.v2;

/**
 * One hook's circuit / budget snapshot for {@code /habitrain roleapi perf}.
 *
 * <p>The breaker is keyed by provider + entry + role + hook type, so the provider
 * and entry ids are carried so a broken slot can be attributed and inspected.
 */
public record HookPerfEntry(
        RoleKey role,
        String hook,
        int failures,
        boolean broken,
        long invocations,
        long lastNanos,
        long budgetNanos,
        String providerId,
        String entryId) {
}
