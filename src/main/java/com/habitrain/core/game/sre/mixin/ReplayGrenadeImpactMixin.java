package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.match.replay.LastMatchReplay.Kind;
import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import io.wifi.starrailexpress.content.entity.GrenadeEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.projectile.Projectile;
import org.agmas.noellesroles.content.entity.DecoyGrenadeEntity;
import org.agmas.noellesroles.content.entity.FlashGrenadeEntity;
import org.agmas.noellesroles.content.entity.IncendiaryGrenadeEntity;
import org.agmas.noellesroles.content.entity.SlimeGrenadeEntity;
import org.agmas.noellesroles.content.entity.SmokeGrenadeEntity;
import org.agmas.noellesroles.content.entity.WallBreakGrenadeEntity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({GrenadeEntity.class, DecoyGrenadeEntity.class, FlashGrenadeEntity.class, IncendiaryGrenadeEntity.class,
        SlimeGrenadeEntity.class, SmokeGrenadeEntity.class, WallBreakGrenadeEntity.class})
abstract class ReplayGrenadeImpactMixin {
    @Inject(method = "onHit", at = @At("HEAD"))
    private void habitrain$captureImpact(HitResult hit, CallbackInfo ci) {
        var grenade = (Projectile) (Object) this;
        if (grenade.level() instanceof ServerLevel level) {
            var owner = grenade.getOwner();
            MatchReplayRecorder.spatial(level, Kind.GRENADE_EXPLOSION, owner == null ? null : owner.getUUID(), null,
                    owner == null ? null : owner.position(), hit.getLocation(), BuiltInRegistries.ENTITY_TYPE.getKey(grenade.getType()).toString(), 0);
        }
    }
}
