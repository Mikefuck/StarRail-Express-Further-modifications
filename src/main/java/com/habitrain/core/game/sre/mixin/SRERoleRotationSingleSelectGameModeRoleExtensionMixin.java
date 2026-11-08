package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.RoleRotationDraftStateAccess;
import com.habitrain.core.game.sre.role.RoleRotationRefreshAccess;
import com.habitrain.core.game.sre.role.SreRoleResolver;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.game.modes.funny.SRERoleRotationSingleSelectGameMode;
import io.wifi.starrailexpress.game.modes.funny.rotation.SingleSelectDraftState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Bridges the role-override refresh to the single-select role-rotation game
 * mode: normalizes the live draft state and re-broadcasts the rotation sync
 * packet when anything changed.
 */
@Mixin(value = SRERoleRotationSingleSelectGameMode.class, remap = false)
public abstract class SRERoleRotationSingleSelectGameModeRoleExtensionMixin
        implements RoleRotationRefreshAccess {

    @Shadow
    private SingleSelectDraftState draftState;

    @Shadow
    private void broadcastSync(ServerLevel world) {
    }

    @Redirect(
            method = "forceFinishRotation",
            at = @At(
                    value = "FIELD",
                    target = "Lio/wifi/starrailexpress/api/TMMRoles;CIVILIAN:Lio/wifi/starrailexpress/api/SRERole;"
            ),
            require = 1,
            remap = false
    )
    private SRERole habitrain$resolveCivilianInForceFinish() {
        return SreRoleResolver.resolveOrOriginal(TMMRoles.CIVILIAN);
    }

    @Override
    public boolean habitrain$refreshRoleExtensions(Level level) {
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
