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
 * 再调 {@code Player.drop(ItemStack,…)}。若只 cancel 后者，槽位已空 → 袋消失 → 失袋即死。
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
        // Safety net: if caller already removed the stack from inv, restore it.
        if (!GreedPouchItem.playerHasOwnPouch(self) && !stack.isEmpty()) {
            if (self.getInventory().add(stack.copy())) {
                stack.setCount(0);
            }
            // inv full: leave stack as-is for caller; still no ItemEntity
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
