package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.match.replay.LastMatchReplay.Kind;
import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import io.wifi.starrailexpress.content.entity.StickyGrenadeEntity;
import io.wifi.starrailexpress.content.entity.TimedGrenadeEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {TimedGrenadeEntity.class, StickyGrenadeEntity.class}, remap = false)
abstract class ReplayFusedGrenadeMixin {
    @Inject(method = "explode", at = @At("HEAD"))
    private void habitrain$captureBlast(CallbackInfo ci) {
        var grenade = (Projectile) (Object) this;
        if (grenade.level() instanceof ServerLevel level) {
            var owner = grenade.getOwner();
            MatchReplayRecorder.spatial(level, Kind.GRENADE_EXPLOSION, owner == null ? null : owner.getUUID(), null,
                    owner == null ? null : owner.position(), grenade.position(),
                    (Object) this instanceof StickyGrenadeEntity ? "sticky_grenade" : "timed_grenade", 0);
        }
    }
}
