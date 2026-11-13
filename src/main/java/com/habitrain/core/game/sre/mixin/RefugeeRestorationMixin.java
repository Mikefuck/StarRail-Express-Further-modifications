package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.rest.EliminatedRestAreaService;
import com.habitrain.core.game.sre.role.sins.component.LustRestrictionService;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pro.fazeclan.river.stupid_express.modifier.refugee.cca.PlayerStatsBeforeRefugee;
import pro.fazeclan.river.stupid_express.modifier.refugee.cca.RefugeeComponent;

/** The refugee snapshot owns inventory and position; finish rest cleanup before it loads either. */
@Mixin(value = PlayerStatsBeforeRefugee.class, remap = false)
public abstract class RefugeeRestorationMixin {
    @Inject(method = "LoadToPlayer", at = @At("HEAD"))
    private static void habitrain$prepareSnapshotRestore(ServerPlayer player, PlayerStatsBeforeRefugee snapshot,
            SRERole role, RefugeeComponent refugee, WorldModifierComponent modifiers, CallbackInfo ci) {
        if (snapshot == null || !snapshot.isAlive()) {
            return;
        }
        // Includes legacy rest occupancy and rest -> spectator round trips. Otherwise
        // setGameMode(ADVENTURE) restores the stale stash over the freshly loaded snapshot,
        // and coordinate-only teleportation can run in the overworld instead of the match.
        EliminatedRestAreaService.prepareUpstreamRevival(player);
        EliminatedRestAreaService.onExplicitUpstreamRevival(player);
    }

    @Inject(method = "LoadToPlayer", at = @At("RETURN"))
    private static void habitrain$finishSnapshotRestore(ServerPlayer player, PlayerStatsBeforeRefugee snapshot,
            SRERole role, RefugeeComponent refugee, WorldModifierComponent modifiers, CallbackInfo ci) {
        if (snapshot != null && snapshot.isAlive()) {
            // A legacy rester may already be in adventure mode, skipping setGameMode.
            LustRestrictionService.onRevival(player);
        }
    }
}
