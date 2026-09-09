package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.component.SwiftWindComponent;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREAbilityPlayerComponent;
import io.wifi.starrailexpress.cca.SREPlayerPsychoComponent;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep the upstream armour, global count and stop lifecycle, replacing only Swift Wind's weapon and duration. */
@Mixin(value = SREPlayerPsychoComponent.class, remap = false)
public abstract class SwiftWindPsychoMixin {
    @ModifyVariable(method = "startPsycho_time", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int habitrain$thirtySecondFrenzy(int time) {
        Player player = ((SREPlayerPsychoComponent) (Object) this).getPlayer();
        return HabiRoles.isHabiRole(player, HabiRoles.SWIFT_WIND) ? 30 * 20 : time;
    }

    @Redirect(method = "startPsycho_time", at = @At(value = "INVOKE",
            target = "Lio/wifi/starrailexpress/api/SRERole;onPsychoGiveItem(Lnet/minecraft/world/entity/player/Player;Lio/wifi/starrailexpress/cca/SREPlayerPsychoComponent;)Z"))
    private boolean habitrain$giveKnives(SRERole role, Player player, SREPlayerPsychoComponent psycho) {
        if (HabiRoles.isHabiRole(player, HabiRoles.SWIFT_WIND)) {
            return SwiftWindComponent.givePsychoKnives(player);
        }
        return role.onPsychoGiveItem(player, psycho);
    }

    @Inject(method = "startPsycho_time", at = @At("RETURN"))
    private void habitrain$refreshSkills(int time, int armour, CallbackInfoReturnable<Boolean> cir) {
        Player player = ((SREPlayerPsychoComponent) (Object) this).getPlayer();
        if (cir.getReturnValueZ() && HabiRoles.isHabiRole(player, HabiRoles.SWIFT_WIND)) {
            SREAbilityPlayerComponent.KEY.get(player).resetAllCooldowns();
            SwiftWindComponent.refreshPsychoKnifeCooldown(player);
        }
    }

    @Inject(method = "serverTick", at = @At("HEAD"))
    private void habitrain$endWhenKnivesRunOut(CallbackInfo ci) {
        SREPlayerPsychoComponent psycho = (SREPlayerPsychoComponent) (Object) this;
        Player player = psycho.getPlayer();
        if (psycho.getPsychoTicks() <= 0 || !HabiRoles.isHabiRole(player, HabiRoles.SWIFT_WIND)) return;
        if (!SwiftWindComponent.hasPsychoKnife(player)) {
            psycho.stopPsychoAndSync();
        }
    }

    @Inject(method = "stopPsycho", at = @At("HEAD"))
    private void habitrain$clearFrenzyKnives(CallbackInfoReturnable<Integer> cir) {
        Player player = ((SREPlayerPsychoComponent) (Object) this).getPlayer();
        // The role may already have changed when the upstream stop lifecycle runs.
        SwiftWindComponent.clearPsychoKnives(player);
    }
}
