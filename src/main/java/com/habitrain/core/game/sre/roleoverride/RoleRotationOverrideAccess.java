package com.habitrain.core.game.sre.roleoverride;

import net.minecraft.world.level.Level;

/**
 * Implemented by the SRE role-rotation game-mode mixins (both rotation modes).
 * The level is needed so a successful refresh can re-broadcast the rotation
 * sync packet to clients.
 */
public interface RoleRotationOverrideAccess {
    boolean habitrain$refreshRoleOverrides(Level level);
}
