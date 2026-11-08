package com.habitrain.core.mixin.rest;

import com.habitrain.core.game.sre.rest.EliminatedRestAreaService;
import com.habitrain.core.game.sre.role.sins.component.LustRestrictionService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tracks physical spectator-to-adventure transitions for rest-area revival and
 * Lust restriction hooks without overriding vanilla {@link ServerPlayer#isSpectator()}.
 */
@Mixin(ServerPlayer.class)
public abstract class RestAreaServerPlayerMixin {
    @Unique private boolean habitrain$wasPhysicalSpectator;
    @Unique private boolean habitrain$wasResting;

    @Inject(method = "setGameMode", at = @At("HEAD"))
    private void habitrain$restoreMatchWorldBeforeUpstreamRevival(GameType gameType,
            CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        habitrain$wasPhysicalSpectator = player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR;
        habitrain$wasResting = EliminatedRestAreaService.isResting(player);
        if (gameType == GameType.ADVENTURE) {
            EliminatedRestAreaService.prepareUpstreamRevival(player);
        }
    }

    // RETURN also covers the already-adventure early return. finishUpstreamRevival
    // is a no-op unless prepareUpstreamRevival marked REVIVING for this UUID.
    @Inject(method = "setGameMode", at = @At("RETURN"))
    private void habitrain$completeUpstreamRevival(GameType gameType, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (gameType == GameType.ADVENTURE) {
            EliminatedRestAreaService.finishUpstreamRevival(player);
            if (habitrain$wasPhysicalSpectator && !EliminatedRestAreaService.isEnteringRest(player)) {
                EliminatedRestAreaService.clearEliminatedOnSpectatorToAdventureRevival(player);
            }
        }
        if ((habitrain$wasPhysicalSpectator || habitrain$wasResting)
                && (gameType == GameType.ADVENTURE || gameType == GameType.SURVIVAL)
                && player.isAlive() && !EliminatedRestAreaService.isEnteringRest(player)) {
            LustRestrictionService.onRevival(player);
        }
    }
}
