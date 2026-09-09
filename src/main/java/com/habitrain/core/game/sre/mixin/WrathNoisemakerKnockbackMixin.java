package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.cca.SREPlayerPsychoComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.agmas.noellesroles.game.roles.innocence.noise_maker.NoiseMakerPlayerComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Unique;

/** Sloth's wake-up anger also calls useAbility, so both shockwave sources share this guard. */
@Mixin(value = NoiseMakerPlayerComponent.class, remap = false)
public abstract class WrathNoisemakerKnockbackMixin {
    @Redirect(method = "useAbility", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;push(DDD)V", remap = true))
    private void habitrain$guardShockwavePush(Player target, double x, double y, double z) {
        if (!habitrain$isBerserkWrath(target)) {
            target.push(x, y, z);
        }
    }

    @Redirect(method = "useAbility", at = @At(value = "INVOKE",
            target = "Lorg/agmas/noellesroles/game/roles/innocence/noise_maker/NoiseMakerPlayerComponent;markShockwavePushed(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void habitrain$guardShockwaveAttribution(ServerPlayer target, ServerPlayer attacker) {
        if (!habitrain$isBerserkWrath(target)) {
            NoiseMakerPlayerComponent.markShockwavePushed(target, attacker);
        }
    }

    @Unique
    private static boolean habitrain$isBerserkWrath(Player target) {
        // Read the live psycho timer: the component's HUD flag may lag by one tick.
        return target instanceof ServerPlayer && !target.isSpectator()
                && HabiRoles.isHabiRole(target, SevenSins.WRATH)
                && SREPlayerPsychoComponent.KEY.get(target).getPsychoTicks() > 0;
    }
}
