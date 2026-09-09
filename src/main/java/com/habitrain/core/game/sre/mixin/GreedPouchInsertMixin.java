package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Both vanilla insertion directions must reject the item before moving any stack. */
@Mixin(BundleItem.class)
public abstract class GreedPouchInsertMixin {
    @Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true)
    private void habitrain$checkSlot(ItemStack pouch, Slot slot, ClickAction action, Player player,
                                     CallbackInfoReturnable<Boolean> cir) {
        habitrain$reject(pouch, slot.getItem(), action, player, cir);
    }

    @Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true)
    private void habitrain$checkCursor(ItemStack pouch, ItemStack other, Slot slot, ClickAction action,
                                       Player player, SlotAccess cursor, CallbackInfoReturnable<Boolean> cir) {
        habitrain$reject(pouch, other, action, player, cir);
    }

    @org.spongepowered.asm.mixin.Unique
    private static void habitrain$reject(ItemStack pouch, ItemStack other, ClickAction action,
                                         Player player, CallbackInfoReturnable<Boolean> cir) {
        if (action != ClickAction.SECONDARY || !GreedPouchItem.isGreedPouch(pouch)
                || other.isEmpty() || GreedPouchItem.canStore(other)) return;
        if (!player.level().isClientSide) {
            player.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.forbidden_item"), true);
        }
        cir.setReturnValue(true);
    }
}
