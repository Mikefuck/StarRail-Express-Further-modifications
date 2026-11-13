package com.habitrain.core.scene.block;

import com.habitrain.core.network.replay.ReplayBoardSettingsPackets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Only a nearby creative player who can build may change this loaded table. */
public final class ReplayBoardSettingsService {
    private ReplayBoardSettingsService() {}

    public static void save(ServerPlayer player, ReplayBoardSettingsPackets.Save request) {
        var level = player.serverLevel();
        var pos = request.pos();
        boolean saved = false;
        if (player.isCreative() && !player.isSpectator() && player.isAlive() && player.mayBuild()
                && level.dimension().location().equals(request.dimension())
                && player.canInteractWithBlock(pos, 1.0) && level.hasChunkAt(pos)
                && level.getBlockState(pos).is(SettlementSummaryBoard.BLOCK)
                && level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker) {
            marker.setSettings(request.settings());
            saved = true;
        }
        ServerPlayNetworking.send(player, new ReplayBoardSettingsPackets.Result(request.dimension(), pos, saved));
    }
}
