package com.habitrain.core.game.sre.roleoverride;

/**
 * Implemented by the role-rotation draft-state mixins (lightning +
 * single-select). Normalizes live rotation structures after an
 * override-snapshot rebuild; returns true when anything changed so the
 * game-mode mixin can re-broadcast the sync packet.
 */
public interface RoleRotationDraftStateAccess {
    boolean habitrain$normalizeRotationState();
}
