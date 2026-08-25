package com.habitrain.core.network;

import org.jetbrains.annotations.Nullable;

/**
 * Dedicated-server gate for {@link ConfigUpdatePayload}.
 *
 * <p>Save-bar then ESC used to send the same JSON twice inside the old 2s
 * {@link C2SRateLimiter} window; the second packet was rejected after the
 * client had already committed, desyncing client and server. Duplicate JSON
 * is now a no-op success. A changed JSON always applies.
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
        if (filteredJson != null && filteredJson.equals(lastAppliedJson)) {
            return Result.SKIP_DUPLICATE;
        }
        return Result.APPLY;
    }
}
