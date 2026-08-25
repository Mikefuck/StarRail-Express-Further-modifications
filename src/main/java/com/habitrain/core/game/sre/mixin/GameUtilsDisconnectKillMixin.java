package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.DisconnectGracePolicy;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the 60s blackout disconnect grace: skip upstream
 * {@code PlayerDiscard} → {@code forceKillPlayer(DISCONNECT)}.
 *
 * <p>Does not cancel murder-mode disconnect kills (blackout alive table empty)
 * or grace-timeout kills ({@code BlackoutDeathHandler.OFFLINE_TIMEOUT_REASON},
 * not {@code DISCONNECT}).</p>
 */
@Mixin(value = GameUtils.class, remap = false)
public abstract class GameUtilsDisconnectKillMixin {

    @Inject(
            method = "killPlayer(Lnet/minecraft/world/entity/player/Player;ZLnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;Z)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$skipDisconnectKillDuringBlackoutGrace(
            Player victim, boolean spawnBody, Player killer,
            ResourceLocation deathReason, boolean forceDeath,
            CallbackInfo ci) {
        if (victim == null || deathReason == null) {
            return;
        }
        if (!GameConstants.DeathReasons.DISCONNECT.equals(deathReason)) {
            return;
        }
        if (!(victim instanceof ServerPlayer serverPlayer)) {
            return;
        }
        try {
            boolean graceEnabled = BlackoutMode.DISCONNECT_GRACE_TICKS > 0;
            boolean blackoutAlive = BlackoutRoleManager.isAliveOnServer(
                    serverPlayer.getServer(), serverPlayer.getUUID());
            if (DisconnectGracePolicy.shouldSkipDisconnectKill(graceEnabled, blackoutAlive)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // fail open: let DISCONNECT kill proceed
        }
    }
}
