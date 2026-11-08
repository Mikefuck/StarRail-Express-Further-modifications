package com.habitrain.core.network;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Legacy duplicate-suppression helpers. The live protocol uses
 * {@link ConfigSubmissionService}'s per-connection request/result cache, including
 * exact request-content verification; these helpers are not an authorization gate.
 *
 * @deprecated Use the acknowledged submission protocol. Retained for binary compatibility.
 */
@Deprecated(forRemoval = false)
public final class ConfigUpdateAdmitPolicy {

    private ConfigUpdateAdmitPolicy() {}

    public enum Result {
        APPLY,
        SKIP_DUPLICATE
    }

    public static Result admit(
            @Nullable String requestId,
            @Nullable String lastRequestId,
            long baseRevision,
            long lastBaseRevision,
            int payloadHash,
            int lastPayloadHash) {
        if (requestId != null && !requestId.isBlank() && requestId.equals(lastRequestId)) {
            return Result.SKIP_DUPLICATE;
        }
        return Result.APPLY;
    }

    public static Result admit(
            String filteredJson,
            @Nullable String lastAppliedJson,
            long nowMs,
            long lastApplyMs,
            long cooldownMs) {
        if (filteredJson == null || filteredJson.isBlank()) {
            return Result.SKIP_DUPLICATE;
        }
        if (cooldownMs > 0 && nowMs == lastApplyMs && Objects.equals(filteredJson, lastAppliedJson)) {
            return Result.SKIP_DUPLICATE;
        }
        return Result.APPLY;
    }
}
