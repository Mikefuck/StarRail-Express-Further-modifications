package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Adds provenance to the exact stack transferred by the upstream thief skill. */
@Mixin(targets = "org.agmas.noellesroles.game.roles.neutral.thief.ThiefPlayerComponent")
public abstract class ThiefStolenItemMarkerMixin {
    @Redirect(method = "stealItem", at = @At(value = "INVOKE",
            target = "Lorg/agmas/noellesroles/utils/RoleUtils;insertStackInFreeSlot(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean habitrain$markStolen(Player player, ItemStack stack) {
        GreedPouchItem.markStolenByThief(stack);
        return org.agmas.noellesroles.utils.RoleUtils.insertStackInFreeSlot(player, stack);
    }
}
