package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.DisconnectGracePolicy;
import com.habitrain.core.game.blackout.ForcedReadyJoinGate;
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

/**
 * Join-position gate for blackout grace and STARTING late-joiners.
 *
 * <p>Upstream {@code adjustPlayerPosition} spectates every {@code ACTIVE} join
 * (no alive check) and leaves {@code STARTING}/{@code INITIATING} on adventure
 * at world spawn, which can pull strangers into {@code getStartingPlayers}.
 * This mixin:</p>
 * <ul>
 *   <li>blackout-alive → cancel (keep logout position/gamemode; no spectator spawn)</li>
 *   <li>STARTING/INITIATING, not in {@link ForcedReadyJoinGate} → spectator teleport
 *       like the ACTIVE branch, then cancel</li>
 *   <li>STARTING/INITIATING, in the snapshot → cancel (leave adventure; do not
 *       yank to world spawn)</li>
 *   <li>ACTIVE and not blackout-alive → original (spectator)</li>
 * </ul>
 */
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
            boolean blackoutAlive = BlackoutRoleManager.isAliveOnServer(
                    serverPlayer.getServer(), serverPlayer.getUUID());
            boolean inSnapshot = ForcedReadyJoinGate.contains(serverPlayer.getUUID());
            boolean spectator = DisconnectGracePolicy.shouldSpectatorOnJoin(
                    statusName, blackoutAlive, inSnapshot);

            if (blackoutAlive) {
                ci.cancel();
                return;
            }
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
