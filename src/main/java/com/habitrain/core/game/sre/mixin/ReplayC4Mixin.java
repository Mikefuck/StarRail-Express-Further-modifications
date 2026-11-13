package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.match.replay.LastMatchReplay.Kind;
import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.game.c4.C4Detonation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = C4Detonation.class, remap = false)
abstract class ReplayC4Mixin {
    @Inject(target = @Desc(value = "detonateAt", args = {ServerLevel.class, Vec3.class, Player.class}), at = @At("HEAD"))
    private static void habitrain$captureBlast(ServerLevel level, Vec3 center, Player source, CallbackInfo ci) {
        MatchReplayRecorder.spatial(level, Kind.BOMB_DETONATE, source == null ? null : source.getUUID(),
                null, source == null ? null : source.position(), center, "c4", 0);
    }

    @Inject(method = "registerThrownCharge", at = @At("TAIL"))
    private static void habitrain$captureThrow(ItemEntity charge, UUID planter, CallbackInfo ci) {
        if (charge != null && planter != null && charge.level() instanceof ServerLevel level) {
            var player = level.getPlayerByUUID(planter);
            MatchReplayRecorder.spatial(level, Kind.BOMB_PLANT, planter, null,
                    player == null ? null : player.position(), charge.position(), "c4_thrown", 0);
        }
    }

    @Inject(method = "defuseBlockCharge", at = @At("RETURN"))
    private static void habitrain$captureDefuse(ServerPlayer player, ItemEntity charge, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) MatchReplayRecorder.spatial(player.serverLevel(), Kind.BOMB_DEFUSE,
                player.getUUID(), null, player.position(), charge.position(), "c4", 0);
    }
}
