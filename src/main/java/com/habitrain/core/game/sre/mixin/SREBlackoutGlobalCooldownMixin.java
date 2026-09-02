package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.config.BlackoutGlobalCooldownRules;
import com.habitrain.core.config.ConfigManager;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Consumer;

/** Replaces only SRE's fan-out blackout cooldown while preserving the user's personal cooldown. */
@Mixin(SREPlayerShopComponent.class)
public abstract class SREBlackoutGlobalCooldownMixin {

    @ModifyArg(
            method = "useBlackout(Lnet/minecraft/world/entity/player/Player;I)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"),
            index = 0,
            require = 1)
    private static Consumer<Player> habitrain$replaceGlobalBlackoutCooldown(Consumer<Player> upstream) {
        int ticks = BlackoutGlobalCooldownRules.toTicks(
                ConfigManager.getInstance().getBlackoutGlobalCooldownSeconds());
        if (ticks <= 0) {
            return ignored -> { };
        }
        return player -> player.getCooldowns().addCooldown(TMMItems.BLACKOUT, ticks);
    }
}
