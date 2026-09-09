package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.GreedEconomy;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = SREPlayerShopComponent.class, remap = false)
public abstract class GreedIncomeMixin {
    @Shadow @Final private Player player;
    @Shadow public int balance;

    @ModifyVariable(method = "setBalance", at = @At("HEAD"), argsOnly = true)
    private int habitrain$shareIncome(int amount) {
        return player instanceof ServerPlayer serverPlayer
                ? GreedEconomy.interceptIncome(serverPlayer, balance, amount) : amount;
    }
}
