package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 取消所有者对绑定贪婪袋的主动丢弃（Q / drop 路径）。
 * <p>
 * 关键：1.21 {@code ServerPlayer.drop(boolean)} 先 {@code Inventory.removeFromSelected}
 * 再调 {@code Player.drop(ItemStack,…)}。若只 cancel 后者，槽位已空，会导致袋子消失。
 * 必须在 {@code ServerPlayer.drop(Z)} HEAD 先 cancel。
 */
@Mixin(ServerPlayer.class)
public class GreedPouchDropMixin {

    /**
     * Hotbar Q / Ctrl-Q 入口：在 removeFromSelected 之前拦截。
     */
    @Inject(
            method = "drop(Z)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void habitrain$cancelGreedPouchHotbarDrop(boolean dropEntireStack,
                                                      CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        ItemStack selected = self.getInventory().getSelected();
        if (!GreedPouchItem.isBoundPouchOf(self, selected)) return;
        cir.setReturnValue(false);
        notifyNoDrop(self);
    }

    /**
     * 直接 drop(ItemStack) 路径（含 Inventory.dropAll / 其它调用方）：仍取消实体生成。
     * Hotbar Q 不能只靠这条，见上面 drop(Z)Z。
     */
    @Inject(
            method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void habitrain$cancelGreedPouchDrop(ItemStack stack, boolean throwRandomly,
                                                boolean retainOwnership,
                                                CallbackInfoReturnable<ItemEntity> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!GreedPouchItem.isBoundPouchOf(self, stack)) return;
        cir.setReturnValue(null);
        int stillInSlot = -1;
        boolean hasOtherOwnPouch = false;
        for (int i = 0; i < self.getInventory().getContainerSize(); i++) {
            ItemStack s = self.getInventory().getItem(i);
            if (s == stack) {
                stillInSlot = i;
            } else if (GreedPouchItem.isBoundPouchOf(self, s)) {
                hasOtherOwnPouch = true;
            }
        }
        // Safety net: if caller already removed the stack from inv, or is about to clear
        // stillInSlot immediately after drop() returns (e.g. GameMode.killPlayer / dropAll), restore it.
        if (!hasOtherOwnPouch && !stack.isEmpty()) {
            ItemStack copy = stack.copy();
            int preferredSlot = stillInSlot >= 0 ? stillInSlot : 0;
            if (stillInSlot >= 0) {
                boolean rescued = false;
                // 1. Prefer an empty slot before stillInSlot (already visited by 0..N loops, never cleared again).
                for (int j = 0; j < stillInSlot; j++) {
                    if (self.getInventory().getItem(j).isEmpty()) {
                        self.getInventory().setItem(j, copy);
                        rescued = true;
                        break;
                    }
                }
                // 2. If stillInSlot == 0 (default slot) or 0..stillInSlot-1 are full, park in a slot > stillInSlot.
                //    If caller only clears stillInSlot, copy stays in j; if caller loops to j after clearing
                //    stillInSlot, step 1 will move copy back into stillInSlot (which is then empty and < j).
                if (!rescued) {
                    for (int j = stillInSlot + 1; j < self.getInventory().items.size(); j++) {
                        if (self.getInventory().items.get(j).isEmpty()) {
                            self.getInventory().items.set(j, copy);
                            rescued = true;
                            break;
                        }
                    }
                }
                if (!rescued && self.getInventory().offhand.get(0).isEmpty() && stillInSlot != 40) {
                    self.getInventory().offhand.set(0, copy);
                    rescued = true;
                }
                // 3. If all slots are occupied (e.g. stillInSlot == 0 and slots 1..35 + offhand are full),
                //    displace slot 1 so at i = 1 the pouch bounces back into slot 0.
                if (!rescued && self.getInventory().items.size() > 1) {
                    int targetSlot = stillInSlot == 0 ? 1 : 0;
                    ItemStack displaced = self.getInventory().items.get(targetSlot);
                    if (!displaced.isEmpty()) {
                        self.drop(displaced, throwRandomly, retainOwnership);
                    }
                    self.getInventory().items.set(targetSlot, copy);
                }
                if (self.getServer() != null) {
                    self.getServer().tell(new net.minecraft.server.TickTask(
                            self.getServer().getTickCount(),
                            () -> com.habitrain.core.game.sre.role.sins.component.GreedComponent
                                    .ensureSingleBoundPouch(self, copy, preferredSlot)));
                }
            } else if (self.getInventory().add(copy)) {
                stack.setCount(0);
            } else {
                // Inventory full: force the pouch back into slot 0 and preserve
                // the displaced item by dropping it, rather than letting the
                // pouch disappear.
                ItemStack displaced = self.getInventory().getItem(0);
                if (!displaced.isEmpty()) {
                    self.drop(displaced, false, false);
                }
                self.getInventory().setItem(0, copy);
                stack.setCount(0);
            }
        }
        notifyNoDrop(self);
    }

    private static void notifyNoDrop(Player self) {
        if (self instanceof ServerPlayer sp) {
            sp.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_greed.no_drop"),
                    true
            );
        }
    }
}
