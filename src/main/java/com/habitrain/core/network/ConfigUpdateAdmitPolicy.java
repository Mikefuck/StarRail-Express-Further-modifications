package com.habitrain.core.network;

import org.jetbrains.annotations.Nullable;

/**
 * Legacy source-compatible policy. Historical JSON content is always reevaluated;
 * request identity, revision conflicts and rate limits live in ConfigSubmissionService.
 */
public final class ConfigUpdateAdmitPolicy {

    private ConfigUpdateAdmitPolicy() {}

    public enum Result {
        APPLY,
        SKIP_DUPLICATE
    }

    public static Result admit(
            String filteredJson,
            @Nullable String lastAppliedJson,
            long nowMs,
            long lastApplyMs,
            long cooldownMs) {
        // Historical content is not request identity: A/X, B/Y, A/X is a valid restore.
        // ConfigSubmissionService handles duplicate request IDs within each connection.
        return Result.APPLY;
    }
}
