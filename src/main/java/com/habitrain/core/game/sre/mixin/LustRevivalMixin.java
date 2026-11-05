package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.LustRestrictionService;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Includes rest-area revivals whose physical game mode was already Adventure. */
@Mixin(value = GameUtils.class, remap = false)
public abstract class LustRevivalMixin {
    @Inject(method = "revivePlayer", at = @At("RETURN"))
    private static void habitrain$revived(ServerPlayer player, double x, double y, double z, CallbackInfo ci) {
        LustRestrictionService.onRevival(player);
    }

    @Inject(method = "revivePlayerToItsRoom", at = @At("RETURN"))
    private static void habitrain$revivedInRoom(ServerPlayer player, CallbackInfo ci) {
        LustRestrictionService.onRevival(player);
    }
}
