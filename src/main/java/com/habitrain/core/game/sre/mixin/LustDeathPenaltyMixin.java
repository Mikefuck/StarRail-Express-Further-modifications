package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.world.entity.player.Player;
import org.agmas.noellesroles.component.DeathPenaltyComponent;
import org.agmas.noellesroles.role.ModRoles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Existing spectator restrictions survive while either Conspirator or Lust is alive. */
@Mixin(value = DeathPenaltyComponent.class, remap = false)
public abstract class LustDeathPenaltyMixin {
    @Redirect(method = "check", at = @At(value = "INVOKE",
            target = "Lio/wifi/starrailexpress/cca/SREGameWorldComponent;isRole(Lnet/minecraft/world/entity/player/Player;Lio/wifi/starrailexpress/api/SRERole;)Z"))
    private boolean habitrain$keepLustRestriction(SREGameWorldComponent game, Player player, SRERole expected) {
        return game.isRole(player, expected) || expected == ModRoles.CONSPIRATOR
                && HabiRoles.isHabiRole(player, SevenSins.LUST);
    }
}
