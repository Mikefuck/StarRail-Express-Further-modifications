package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.roleoverride.RoleRotationDraftStateAccess;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
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
 * <p>Soft (require = 0): if SRE later removes or renames these reads, the
 * override just degrades to the vanilla civilian.
 */
@Mixin(value = SingleSelectDraftState.class, remap = false)
public abstract class SingleSelectDraftStateRoleOverrideMixin implements RoleRotationDraftStateAccess {

    @Shadow
    @Final
    public ArrayList<SRERole> rolePool;

    @Shadow
    @Final
    public Map<UUID, SRERole> selectedRoles;

    @Shadow
    private ArrayList<SRERole> currentCandidates;

    @Redirect(
            method = {
                    "initializeRolePool",
                    "selectRandomRole",
                    "timeoutUnfinishedPlayers",
                    "adjustRemainingRoles"
            },
            at = @At(
                    value = "FIELD",
                    target = "Lio/wifi/starrailexpress/api/TMMRoles;CIVILIAN:Lio/wifi/starrailexpress/api/SRERole;"
            ),
            require = 0
    )
    private static SRERole habitrain$resolveCivilianDirect() {
        return SreRoleOverrideResolver.resolveOrOriginal(TMMRoles.CIVILIAN);
    }

    @Redirect(
            method = "lambda$initializeRolePool$*",
            at = @At(
                    value = "FIELD",
                    target = "Lio/wifi/starrailexpress/api/TMMRoles;CIVILIAN:Lio/wifi/starrailexpress/api/SRERole;"
            ),
            require = 0
    )
    private static SRERole habitrain$resolveCivilianInLambda() {
        return SreRoleOverrideResolver.resolveOrOriginal(TMMRoles.CIVILIAN);
    }

    @Override
    public boolean habitrain$normalizeRotationState() {
        boolean changed = false;

        ArrayList<SRERole> newPool = new ArrayList<>();
        for (SRERole role : rolePool) {
            SRERole resolved = SreRoleOverrideResolver.resolve(role);
            if (resolved != null && resolved.identifier() != null
                    && SreRoleOverrideResolver.isVisible(resolved)) {
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
                SRERole resolved = SreRoleOverrideResolver.resolve(role);
                if (resolved != null && resolved.identifier() != null
                        && SreRoleOverrideResolver.isVisible(resolved)) {
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
            SRERole resolved = SreRoleOverrideResolver.resolve(entry.getValue());
            if (resolved != null && resolved != entry.getValue()) {
                entry.setValue(resolved);
                changed = true;
            }
        }

        return changed;
    }
}
