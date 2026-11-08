package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.match.MatchEventBridge;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Preserve upstream winner decisions and stats; followers read the completed decisions last. */
@Mixin(value = GameUtils.class, remap = false)
public abstract class LustFollowVictoryMixin {
    @Redirect(method = "recordWinStats", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;", remap = true))
    private static List<ServerPlayer> habitrain$settleLustLast(ServerLevel level) {
        List<ServerPlayer> ordered = new ArrayList<>(level.players());
        ordered.sort(Comparator.comparing(p -> HabiRoles.isHabiRole(p, SevenSins.LUST)));
        return ordered;
    }

    @Inject(method = "finalizeGame", at = @At("HEAD"))
    private static void habitrain$beginFinalizeGame(ServerLevel world, CallbackInfo ci) {
        MatchEventBridge.beginFinalizeGame(world);
    }

    @Inject(method = "finalizeGame", at = @At(
            value = "INVOKE",
            target = "Lio/wifi/starrailexpress/api/GameMode;recordWinStats(Lnet/minecraft/server/level/ServerLevel;Lio/wifi/starrailexpress/cca/SREGameRoundEndComponent;Lio/wifi/starrailexpress/cca/SREGameWorldComponent;)V",
            shift = At.Shift.AFTER))
    private static void habitrain$afterRecordWinStats(ServerLevel world, CallbackInfo ci) {
        SREGameWorldComponent game = null;
        try {
            game = SREGameWorldComponent.KEY.get(world);
        } catch (RuntimeException ignored) {
        }
        MatchEventBridge.onAfterRecordWinStats(world, game);
    }

    @Inject(method = "finalizeGame", at = @At("RETURN"))
    private static void habitrain$endFinalizeGame(ServerLevel world, CallbackInfo ci) {
        SREGameWorldComponent game = null;
        try {
            game = SREGameWorldComponent.KEY.get(world);
        } catch (RuntimeException ignored) {
        }
        MatchEventBridge.endFinalizeGame(world, game);
    }
}

