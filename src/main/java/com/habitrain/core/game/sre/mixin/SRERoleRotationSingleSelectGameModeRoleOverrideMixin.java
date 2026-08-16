package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.roleoverride.RoleRotationDraftStateAccess;
import com.habitrain.core.game.sre.roleoverride.RoleRotationOverrideAccess;
import io.wifi.starrailexpress.game.modes.funny.SRERoleRotationSingleSelectGameMode;
import io.wifi.starrailexpress.game.modes.funny.rotation.SingleSelectDraftState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Bridges the role-override refresh to the single-select role-rotation game
 * mode: normalizes the live draft state and re-broadcasts the rotation sync
 * packet when anything changed.
 */
@Mixin(value = SRERoleRotationSingleSelectGameMode.class, remap = false)
public abstract class SRERoleRotationSingleSelectGameModeRoleOverrideMixin
        implements RoleRotationOverrideAccess {

    @Shadow
    private SingleSelectDraftState draftState;

    @Shadow
    private void broadcastSync(ServerLevel world) {
    }

    @Override
    public boolean habitrain$refreshRoleOverrides(Level level) {
        if (draftState == null) {
            return false;
        }
        if (!(draftState instanceof RoleRotationDraftStateAccess access)) {
            return false;
        }
        boolean changed = access.habitrain$normalizeRotationState();
        if (changed && level instanceof ServerLevel serverLevel) {
            broadcastSync(serverLevel);
        }
        return changed;
    }
}
