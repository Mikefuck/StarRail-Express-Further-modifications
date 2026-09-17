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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;

/** Both vanilla insertion directions must reject the item before moving any stack. */
@Mixin(value = BundleItem.class, priority = 500)
public abstract class GreedPouchInsertMixin {
    @Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true)
    private void habitrain$checkSlot(ItemStack pouch, Slot slot, ClickAction action, Player player,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (action != ClickAction.SECONDARY || !GreedPouchItem.isGreedPouch(pouch)) return;
        cir.setReturnValue(true);
        if (!slot.allowModification(player)) return;
        if (slot.getItem().isEmpty()) {
            ItemStack first = pouch.getOrDefault(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS,
                    net.minecraft.world.item.component.BundleContents.EMPTY)
                    .itemCopyStream().findFirst().orElse(ItemStack.EMPTY);
            if (first.isEmpty() || !slot.mayPlace(first)) return;
            ItemStack removed = GreedPouchItem.removeFirst(pouch);
            if (!removed.isEmpty()) slot.setByPlayer(removed);
        } else {
            habitrain$reject(pouch, slot.getItem(), action, player, cir);
            GreedPouchItem.insert(pouch, slot.getItem());
            slot.setChanged();
        }
    }

    @Inject(method = "appendHoverText", at = @At("HEAD"), cancellable = true)
    private void habitrain$countTooltip(ItemStack pouch, Item.TooltipContext context,
                                        List<Component> lines, TooltipFlag flag, CallbackInfo ci) {
        if (!GreedPouchItem.isGreedPouch(pouch)) return;
        lines.add(Component.literal("容量：" + GreedPouchItem.storedCount(pouch) + "/32件（每件占1格）"));
        ci.cancel();
    }

    @ModifyReturnValue(method = "getBarWidth", at = @At("RETURN"))
    private int habitrain$countBar(int original, ItemStack pouch) {
        return GreedPouchItem.isGreedPouch(pouch)
                ? Math.min(13, 1 + 12 * GreedPouchItem.storedCount(pouch) / GreedPouchItem.CAPACITY) : original;
    }

    @ModifyReturnValue(method = "getFullnessDisplay", at = @At("RETURN"))
    private static float habitrain$countFullness(float original, ItemStack pouch) {
        return GreedPouchItem.isGreedPouch(pouch)
                ? Math.min(1.0F, (float) GreedPouchItem.storedCount(pouch) / GreedPouchItem.CAPACITY) : original;
    }

    @Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true)
    private void habitrain$checkCursor(ItemStack pouch, ItemStack other, Slot slot, ClickAction action,
                                       Player player, SlotAccess cursor, CallbackInfoReturnable<Boolean> cir) {
        if (action != ClickAction.SECONDARY || !GreedPouchItem.isGreedPouch(pouch)) return;
        cir.setReturnValue(true);
        if (!slot.allowModification(player)) return;
        if (other.isEmpty()) {
            cursor.set(GreedPouchItem.removeFirst(pouch));
        } else {
            habitrain$reject(pouch, other, action, player, cir);
            GreedPouchItem.insert(pouch, other);
        }
        slot.setChanged();
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
