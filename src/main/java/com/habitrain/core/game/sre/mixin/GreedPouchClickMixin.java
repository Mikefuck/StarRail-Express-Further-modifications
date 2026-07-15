package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
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

/**
 * 阻止把绑定贪婪袋拖出玩家背包（丢到其他容器/地面槽）。
 * 背包内整理仍允许。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class GreedPouchClickMixin {

    @Shadow
    public abstract ItemStack getCarried();

    @Inject(
            method = "clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void habitrain$blockGreedPouchExport(int slotId, int button, ClickType clickType,
                                                 Player player, CallbackInfo ci) {
        if (player == null || player.level().isClientSide()) return;
        if (!(player instanceof ServerPlayer sp)) return;

        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        ItemStack carried = getCarried();
        boolean carriedOwn = GreedPouchItem.isBoundPouchOf(sp, carried);

        // THROW with own pouch on cursor
        if (clickType == ClickType.THROW && carriedOwn) {
            cancel(sp, ci);
            return;
        }

        if (slotId < 0) {
            // outside click drops carried
            if (carriedOwn) {
                cancel(sp, ci);
            }
            return;
        }

        if (slotId >= menu.slots.size()) return;
        Slot slot = menu.slots.get(slotId);
        if (slot == null) return;

        ItemStack slotStack = slot.getItem();
        boolean slotOwn = GreedPouchItem.isBoundPouchOf(sp, slotStack);
        boolean slotIsPlayerInv = slot.container == sp.getInventory();

        // Moving own pouch into non-player container
        if (carriedOwn && !slotIsPlayerInv) {
            cancel(sp, ci);
            return;
        }

        // Shift-click own pouch out of player inv into other container
        if (clickType == ClickType.QUICK_MOVE && slotOwn && slotIsPlayerInv) {
            if (menu.slots.size() > 46) {
                cancel(sp, ci);
                return;
            }
        }

        // THROW from slot (Q on slot)
        if (clickType == ClickType.THROW && slotOwn) {
            cancel(sp, ci);
        }
    }

    private static void cancel(ServerPlayer sp, CallbackInfo ci) {
        ci.cancel();
        sp.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_greed.no_drop"),
                true
        );
        sp.containerMenu.broadcastChanges();
    }
}
