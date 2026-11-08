package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.RoleRotationDraftStateAccess;
import com.habitrain.core.game.sre.role.SreRoleResolver;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.game.modes.funny.rotation.SingleSelectDraftState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * Makes every {@link TMMRoles#CIVILIAN} read during single-select role
 * rotation resolve to the current replacement snapshot, and exposes
 * live-structure normalization for the game-mode override refresh.
 *
 * <p>Lambda targets are the verified SRE 4.3.0 bytecode names so Loom can
 * validate them during build.
 */
@Mixin(value = SingleSelectDraftState.class, remap = false)
public abstract class SingleSelectDraftStateRoleExtensionMixin implements RoleRotationDraftStateAccess {

    @Shadow
    @Final
    public ArrayList<SRERole> rolePool;

    @Shadow
    @Final
    public Map<UUID, SRERole> selectedRoles;

    @Shadow
    private ArrayList<SRERole> currentCandidates;

    @Redirect(
            method = "*",
            at = @At(
                    value = "FIELD",
                    target = "Lio/wifi/starrailexpress/api/TMMRoles;CIVILIAN:Lio/wifi/starrailexpress/api/SRERole;"
            ),
            require = 1
    )
    private static SRERole habitrain$resolveCivilianDirect() {
        return SreRoleResolver.resolveOrOriginal(TMMRoles.CIVILIAN);
    }

    @Redirect(
            method = "initializeRolePool",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/agmas/harpymodloader/commands/RoleCountManager;getVigilanteCount(I)I"
            ),
            require = 1,
            remap = false
    )
    private static int habitrain$resolveVigilanteCount(int playerCount) {
        int forced = org.agmas.harpymodloader.commands.RoleCountManager.forcedVigilanteCount;
        if (forced >= 0) {
            return Math.min(forced, playerCount);
        }
        int divisor = Math.max(1, com.habitrain.core.config.ConfigManager.getInstance().getSheriffCountDivisor());
        return playerCount / divisor;
    }

    @Redirect(
            method = "adjustRemainingRoles",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/agmas/harpymodloader/commands/RoleCountManager;getVigilanteCount(I)I"
            ),
            require = 1,
            remap = false
    )
    private static int habitrain$resolveRemainingVigilanteCount(int playerCount) {
        return habitrain$resolveVigilanteCount(playerCount);
    }

    @Override
    public boolean habitrain$normalizeRotationState() {
        boolean changed = false;

        ArrayList<SRERole> newPool = new ArrayList<>();
        for (SRERole role : rolePool) {
            SRERole resolved = SreRoleResolver.resolve(role);
            if (resolved != null && resolved.identifier() != null
                    && SreRoleResolver.isVisible(resolved)) {
                newPool.add(resolved);
                changed |= resolved != role;
            } else {
                changed = true;
            }
        }
        rolePool.clear();
        rolePool.addAll(newPool);

        if (currentCandidates != null) {
            ArrayList<SRERole> newCandidates = new ArrayList<>();
            for (SRERole role : currentCandidates) {
                SRERole resolved = SreRoleResolver.resolve(role);
                if (resolved != null && resolved.identifier() != null
                        && SreRoleResolver.isVisible(resolved)) {
                    newCandidates.add(resolved);
                    changed |= resolved != role;
                } else {
                    changed = true;
                }
            }
            currentCandidates.clear();
            currentCandidates.addAll(newCandidates);
        }

        for (Map.Entry<UUID, SRERole> entry : selectedRoles.entrySet()) {
            SRERole resolved = SreRoleResolver.resolve(entry.getValue());
            if (resolved != null && resolved != entry.getValue()) {
                entry.setValue(resolved);
                changed = true;
            }
        }

        return changed;
    }
}
