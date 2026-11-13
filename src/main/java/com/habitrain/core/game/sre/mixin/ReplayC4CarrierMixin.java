package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.match.replay.LastMatchReplay.Kind;
import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.agmas.noellesroles.cca.C4BackComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = C4BackComponent.class, remap = false)
abstract class ReplayC4CarrierMixin {
    @Shadow @Final private Level level;

    @Inject(method = "addC4(Ljava/util/UUID;JLjava/util/UUID;)Z", at = @At("RETURN"))
    private void habitrain$capturePlant(UUID carrier, long fuse, UUID planter, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && level instanceof ServerLevel server) {
            var target = server.getPlayerByUUID(carrier);
            var actor = planter == null ? null : server.getPlayerByUUID(planter);
            MatchReplayRecorder.spatial(server, Kind.BOMB_PLANT, planter, carrier, actor == null ? null : actor.position(),
                    target == null ? null : target.position(), "c4", 0);
        }
    }
}
