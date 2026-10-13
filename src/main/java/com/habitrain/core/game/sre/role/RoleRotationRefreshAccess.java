package com.habitrain.core.game.sre.role;

import net.minecraft.world.level.Level;

/**
 * Implemented by the SRE role-rotation game-mode mixins (both rotation modes).
 * The level is needed so a successful refresh can re-broadcast the rotation
 * sync packet to clients.
 */
public interface RoleRotationRefreshAccess {
    boolean habitrain$refreshRoleExtensions(Level level);
}
