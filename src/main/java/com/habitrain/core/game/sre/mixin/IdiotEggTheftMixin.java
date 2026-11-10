package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.idiot.IdiotEggItem;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.agmas.noellesroles.game.roles.neutral.thief.ThiefPlayerComponent", remap = false)
public abstract class IdiotEggTheftMixin {
    @Inject(method = "canStealItem", at = @At("HEAD"), cancellable = true)
    private void habitrain$boundEgg(ItemStack stack, Player target, SREGameWorldComponent game, CallbackInfoReturnable<Boolean> cir) {
        if (stack.getItem() instanceof IdiotEggItem) cir.setReturnValue(false);
    }
}
