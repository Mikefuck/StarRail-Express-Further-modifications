package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.ForcedReadyJoinGate;
import io.wifi.starrailexpress.PlayerJoinUtils;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the confirmed roster in place during STARTING; late joiners spectate. */
@Mixin(value = PlayerJoinUtils.class, remap = false)
public abstract class PlayerJoinUtilsMixin {

    @Inject(method = "adjustPlayerPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$gateJoinPosition(ServerPlayer serverPlayer, CallbackInfo ci) {
        if (serverPlayer == null) {
            return;
        }
        try {
            SREGameWorldComponent gameWorldComponent =
                    SREGameWorldComponent.KEY.get(serverPlayer.level());
            if (gameWorldComponent == null || gameWorldComponent.getGameStatus() == null) {
                return;
            }
            String statusName = gameWorldComponent.getGameStatus().name();
            boolean spectator = !ForcedReadyJoinGate.contains(serverPlayer.getUUID());
            if ("STARTING".equals(statusName) || "INITIATING".equals(statusName)) {
                ci.cancel();
                if (spectator) {
                    habitrain$teleportToSpectatorSpawn(serverPlayer);
                }
            }
        } catch (Throwable ignored) {
            // fail open: original adjustPlayerPosition
        }
    }

    private static void habitrain$teleportToSpectatorSpawn(ServerPlayer serverPlayer) {
        if (!(serverPlayer.level() instanceof ServerLevel serverWorld)) {
            return;
        }
        AreasWorldComponent areas = AreasWorldComponent.KEY.get(serverWorld);
        AreasWorldComponent.PosWithOrientation spectatorSpawnPos =
                areas != null ? areas.getSpectatorSpawnPos() : null;
        if (spectatorSpawnPos != null && spectatorSpawnPos.pos != null) {
            serverPlayer.teleportTo(serverWorld, spectatorSpawnPos.pos.x(), spectatorSpawnPos.pos.y(),
                    spectatorSpawnPos.pos.z(), spectatorSpawnPos.yaw, spectatorSpawnPos.pitch);
        }
        serverPlayer.setGameMode(GameType.SPECTATOR);
    }
}
