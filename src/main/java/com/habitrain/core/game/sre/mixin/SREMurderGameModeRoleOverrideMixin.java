package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects SRE's hard-coded base-role constants through the live override
 * snapshot. This covers both fallback assignment and identity checks.
 */
@Mixin(targets = "io.wifi.starrailexpress.game.modes.SREMurderGameMode", remap = false)
public abstract class SREMurderGameModeRoleOverrideMixin {
    @Redirect(
            method = {
                    "assignRole",
                    "getAllRoles",
                    "assignRolesToPlayers",
                    "lambda$getAllRoles$9",
                    "lambda$getAllRoles$8",
                    "lambda$getAllRoles$6",
                    "lambda$assignRole$0"
            },
            at = @At(
                    value = "FIELD",
                    target = "Lio/wifi/starrailexpress/api/TMMRoles;CIVILIAN:Lio/wifi/starrailexpress/api/SRERole;"
            ),
            require = 0
    )
    private static SRERole habitrain$resolveCivilian() {
        return SreRoleOverrideResolver.resolveOrOriginal(TMMRoles.CIVILIAN);
    }

    @Redirect(
            method = "tickServerGameLoop",
            at = @At(
                    value = "FIELD",
                    target = "Lio/wifi/starrailexpress/api/TMMRoles;VIGILANTE:Lio/wifi/starrailexpress/api/SRERole;"
            ),
            require = 0
    )
    private SRERole habitrain$resolveVigilante() {
        return SreRoleOverrideResolver.resolveOrOriginal(TMMRoles.VIGILANTE);
    }

    @Redirect(
            method = "tickServerGameLoop",
            at = @At(
                    value = "FIELD",
                    target = "Lio/wifi/starrailexpress/api/TMMRoles;LOOSE_END:Lio/wifi/starrailexpress/api/SRERole;"
            ),
            require = 0
    )
    private SRERole habitrain$resolveLooseEnd() {
        return SreRoleOverrideResolver.resolveOrOriginal(TMMRoles.LOOSE_END);
    }
}
