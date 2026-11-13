package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.match.replay.LastMatchReplay.Kind;
import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import io.wifi.starrailexpress.cca.SREPlayerPoisonComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = SREPlayerPoisonComponent.class, remap = false)
abstract class ReplayPoisonMixin {
    @Inject(method = "setPoisonTicks", at = @At("TAIL"))
    private void habitrain$poisonApplied(int ticks, UUID actor, CallbackInfo ci) {
        Player player = ((SREPlayerPoisonComponent) (Object) this).getPlayer();
        if (ticks > 0 && player.level() instanceof ServerLevel level) {
            Player source = actor == null ? null : level.getPlayerByUUID(actor);
            MatchReplayRecorder.spatial(level, Kind.POISON, actor, player.getUUID(),
                    source == null ? null : source.position(), player.position(), "applied", 0);
        }
    }
}
