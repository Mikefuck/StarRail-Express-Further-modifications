package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.roleoverride.RoleRotationDraftStateAccess;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.game.modes.funny.rotation.LightningDraftState;
import io.wifi.starrailexpress.game.utils.RoleInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Makes every {@link TMMRoles#CIVILIAN} read during lightning role-rotation
 * pool construction / selection resolve to the current replacement snapshot,
 * and exposes live-structure normalization for the game-mode override refresh.
 *
 * <p>New SRE holds rotation state in {@link LightningDraftState} instead of the
 * removed {@code cca.gamemode.RoleRotationWorldComponent}. Direct reads and
 * pool-predicate lambda reads are both redirected (wildcard lambda targets,
 * soft require = 0: if SRE later removes or renames them, the override just
 * degrades to the vanilla civilian).
 */
@Mixin(value = LightningDraftState.class, remap = false)
public abstract class LightningDraftStateRoleOverrideMixin implements RoleRotationDraftStateAccess {

    @Shadow
    @Final
    public ArrayList<RoleInstance> rolePool;

    @Shadow
    @Final
    public Map<UUID, SRERole> selectedRoles;

    @Shadow
    @Final
    public Set<SRERole> canReplaceRole;

    @Shadow
    public Map<UUID, List<RoleInstance>> roundCandidates;

    @Redirect(
            method = {
                    "initializeRolePool",
                    "startNextRound",
                    "selectRandomRole",
                    "adjustRoles"
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

        ArrayList<RoleInstance> newPool = new ArrayList<>();
        for (RoleInstance ri : rolePool) {
            SRERole resolved = SreRoleOverrideResolver.resolve(ri.role());
            if (resolved != null && resolved.identifier() != null
                    && SreRoleOverrideResolver.isVisible(resolved)) {
                newPool.add(new RoleInstance(ri.uuid(), resolved));
                changed |= resolved != ri.role();
            } else {
                changed = true;
            }
        }
        rolePool.clear();
        rolePool.addAll(newPool);

        if (roundCandidates != null) {
            for (Map.Entry<UUID, List<RoleInstance>> entry : roundCandidates.entrySet()) {
                List<RoleInstance> newList = new ArrayList<>();
                for (RoleInstance ri : entry.getValue()) {
                    SRERole resolved = SreRoleOverrideResolver.resolve(ri.role());
                    if (resolved != null && resolved.identifier() != null
                            && SreRoleOverrideResolver.isVisible(resolved)) {
                        newList.add(new RoleInstance(ri.uuid(), resolved));
                        changed |= resolved != ri.role();
                    } else {
                        changed = true;
                    }
                }
                entry.setValue(newList);
            }
        }

        for (Map.Entry<UUID, SRERole> entry : selectedRoles.entrySet()) {
            SRERole resolved = SreRoleOverrideResolver.resolve(entry.getValue());
            if (resolved != null && resolved != entry.getValue()) {
                entry.setValue(resolved);
                changed = true;
            }
        }

        Set<SRERole> newReplaceable = new LinkedHashSet<>();
        for (SRERole role : canReplaceRole) {
            SRERole resolved = SreRoleOverrideResolver.resolveOrOriginal(role);
            if (resolved != null) {
                newReplaceable.add(resolved);
                changed |= resolved != role;
            }
        }
        canReplaceRole.clear();
        canReplaceRole.addAll(newReplaceable);

        return changed;
    }
}
