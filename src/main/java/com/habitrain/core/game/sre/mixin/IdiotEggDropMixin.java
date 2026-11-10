package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.idiot.IdiotService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class IdiotEggDropMixin {
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void habitrain$keepEgg(boolean entire, CallbackInfoReturnable<Boolean> cir) {
        if (IdiotService.isEgg(((ServerPlayer) (Object) this).getInventory().getSelected())) cir.setReturnValue(false);
    }
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void habitrain$neverDropEgg(ItemStack stack, boolean random, boolean retain, CallbackInfoReturnable<ItemEntity> cir) {
        if (IdiotService.isEgg(stack)) cir.setReturnValue(null);
    }
}
