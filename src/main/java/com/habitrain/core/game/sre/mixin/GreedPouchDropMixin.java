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
 */
@Mixin(Player.class)
public class GreedPouchDropMixin {

    @Inject(
            method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void habitrain$cancelGreedPouchDrop(ItemStack stack, boolean throwRandomly,
                                                boolean retainOwnership,
                                                CallbackInfoReturnable<ItemEntity> cir) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide()) return;
        if (!GreedPouchItem.isBoundPouchOf(self, stack)) return;
        cir.setReturnValue(null);
        if (self instanceof ServerPlayer sp) {
            sp.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_greed.no_drop"),
                    true
            );
        }
    }
}
