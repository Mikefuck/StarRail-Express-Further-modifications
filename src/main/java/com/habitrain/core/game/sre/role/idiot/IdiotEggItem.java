package com.habitrain.core.game.sre.role.idiot;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import java.util.List;

public final class IdiotEggItem extends Item {
    public IdiotEggItem() { super(new Properties().stacksTo(1)); }
    @Override public boolean canFitInsideContainerItems() { return false; }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.fail(stack);
        if (level.isClientSide) {
            player.getCooldowns().addCooldown(this, IdiotRules.THROW_TICKS);
            return InteractionResultHolder.success(stack);
        }
        return IdiotService.throwEgg(player, stack) ? InteractionResultHolder.success(stack) : InteractionResultHolder.fail(stack);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.habitrain_core.idiot_egg.desc"));
        lines.add(Component.translatable("item.habitrain_core.idiot_egg.bound"));
    }
}
