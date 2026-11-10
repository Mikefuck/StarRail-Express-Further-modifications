package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.idiot.IdiotService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class IdiotEggContainerMixin {
    @Shadow public abstract ItemStack getCarried();
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void habitrain$boundEgg(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        boolean carried = IdiotService.isEgg(getCarried());
        Slot slot = slotId >= 0 && slotId < menu.slots.size() ? menu.slots.get(slotId) : null;
        boolean inSlot = slot != null && IdiotService.isEgg(slot.getItem());
        boolean external = slot != null && slot.container != player.getInventory();
        boolean swapped = type == ClickType.SWAP && (button >= 0 && button < 9 || button == 40)
                && IdiotService.isEgg(player.getInventory().getItem(button));
        boolean block = external && (carried || inSlot || swapped)
                || carried && (slotId < 0 || type == ClickType.QUICK_CRAFT)
                || type == ClickType.THROW && inSlot
                || type == ClickType.QUICK_MOVE && inSlot && menu.slots.stream().anyMatch(s -> s.container != player.getInventory());
        if (!block) return;
        ci.cancel();
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(Component.translatable("item.habitrain_core.idiot_egg.bound"), true);
            serverPlayer.containerMenu.broadcastChanges();
        }
    }
}
