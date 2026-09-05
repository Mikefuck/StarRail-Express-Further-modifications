package com.habitrain.core.scene.server;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;

import java.util.Objects;
import java.util.UUID;

/** Server-authoritative identity and state for one diagnostic staging asset. */
public final class SceneStagingSession {
    public enum ValidationFailure {
        NONE,
        EXPIRED,
        IDENTITY_MISMATCH,
        PERMISSION_LOST,
        TOOL_SESSION_CHANGED,
        MAP_CHANGED,
        DIMENSION_CHANGED
    }

    private final String stagingId;
    private final UUID requesterId;
    private final String mapKey;
    private final String dimensionId;
    private final String toolSessionId;
    private final SceneAssetDescriptor descriptor;
    private final long expiresAt;
    private boolean reportAccepted;
    private String clientEnvironment = "";

    public SceneStagingSession(String stagingId, UUID requesterId, String mapKey,
                               String dimensionId, String toolSessionId,
                               SceneAssetDescriptor descriptor, long expiresAt) {
        this.stagingId = Objects.requireNonNull(stagingId);
        this.requesterId = Objects.requireNonNull(requesterId);
        this.mapKey = mapKey == null || mapKey.isBlank() ? "__default__" : mapKey.trim();
        this.dimensionId = dimensionId == null ? "" : dimensionId;
        this.toolSessionId = toolSessionId == null ? "" : toolSessionId;
        this.descriptor = Objects.requireNonNull(descriptor);
        this.expiresAt = expiresAt;
    }

    public String stagingId() { return stagingId; }
    public UUID requesterId() { return requesterId; }
    public String mapKey() { return mapKey; }
    public String dimensionId() { return dimensionId; }
    public String toolSessionId() { return toolSessionId; }
    public SceneAssetDescriptor descriptor() { return descriptor; }
    public long expiresAt() { return expiresAt; }
    public boolean reportAccepted() { return reportAccepted; }
    public String clientEnvironment() { return clientEnvironment; }

    public ValidationFailure validate(UUID actorId, boolean hasOp2, String suppliedStagingId,
                                      String suppliedMapKey, String suppliedHash,
                                      String currentToolSessionId, String currentEditorMapKey,
                                      String currentDimensionId, long now) {
        if (now >= expiresAt) return ValidationFailure.EXPIRED;
        if (!requesterId.equals(actorId)
                || !stagingId.equals(suppliedStagingId)
                || !mapKey.equals(suppliedMapKey)
                || !descriptor.sha256().equalsIgnoreCase(suppliedHash == null ? "" : suppliedHash)) {
            return ValidationFailure.IDENTITY_MISMATCH;
        }
        if (!hasOp2) return ValidationFailure.PERMISSION_LOST;
        if (!toolSessionId.equals(currentToolSessionId)) return ValidationFailure.TOOL_SESSION_CHANGED;
        if (!mapKey.equals(currentEditorMapKey)) return ValidationFailure.MAP_CHANGED;
        if (!dimensionId.equals(currentDimensionId)) return ValidationFailure.DIMENSION_CHANGED;
        return ValidationFailure.NONE;
    }

    public boolean acceptStrictReport(boolean success, String registryFingerprint,
                                      String clientEnvironment) {
        reportAccepted = success
                && descriptor.fingerprint().equals(registryFingerprint == null ? "" : registryFingerprint);
        this.clientEnvironment = clientEnvironment == null ? "" : clientEnvironment;
        return reportAccepted;
    }
}
