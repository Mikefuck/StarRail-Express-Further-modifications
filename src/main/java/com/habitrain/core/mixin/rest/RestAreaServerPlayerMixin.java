package com.habitrain.core.mixin.rest;

import com.habitrain.core.game.sre.EliminatedRestAreaService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A resting player has Adventure movement in the post-game area, but remains a
 * spectator to all server-side SRE checks until an upstream revival completes.
 */
@Mixin(ServerPlayer.class)
public abstract class RestAreaServerPlayerMixin {

    @Inject(method = "isSpectator", at = @At("HEAD"), cancellable = true)
    private void habitrain$keepRestingPlayersEliminated(CallbackInfoReturnable<Boolean> cir) {
        if (EliminatedRestAreaService.isResting((ServerPlayer) (Object) this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setGameMode", at = @At("HEAD"))
    private void habitrain$restoreMatchWorldBeforeUpstreamRevival(GameType gameType,
            CallbackInfoReturnable<Boolean> cir) {
        if (gameType == GameType.ADVENTURE) {
            EliminatedRestAreaService.prepareUpstreamRevival((ServerPlayer) (Object) this);
        }
    }

    // RETURN also covers the early-return path used when the player is already
    // physically in Adventure mode, as is the case for rest-area players.
    @Inject(method = "setGameMode", at = @At("RETURN"))
    private void habitrain$completeUpstreamRevival(GameType gameType, CallbackInfoReturnable<Boolean> cir) {
        if (gameType == GameType.ADVENTURE) {
            EliminatedRestAreaService.finishUpstreamRevival((ServerPlayer) (Object) this);
        }
    }
}
