package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoles;
import io.wifi.starrailexpress.content.item.KnifeItem;
import io.wifi.starrailexpress.game.KillerKnifeDurability;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 默剧杀手被动：取消举刀准备音效。
 * mime 角色才接管；非 mime 直接 return 不 cancel。
 * HEAD cancel 后复刻上游 {@code KnifeItem.use} 的耐久耗尽校验，不播放 ITEM_KNIFE_PREPARE。
 */
@Mixin(value = KnifeItem.class, remap = false)
public class MimeKnifeSoundMixin {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void habitrain$silentRaise(Level world, Player user, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (user == null) {
            return;
        }
        if (!HabiRoles.isHabiRole(user, HabiRoles.MIME_KILLER)) {
            return;
        }
        ItemStack stack = user.getItemInHand(hand);
        if (!world.isClientSide) {
            boolean durabilityKnife = KillerKnifeDurability.isDurabilityModeEnabled(user.level())
                    && KillerKnifeDurability.isMarkedKnife(stack);
            if (durabilityKnife && KillerKnifeDurability.isDepleted(stack)) {
                user.displayClientMessage(
                        Component.translatable("message.sre.knife.depleted").withStyle(ChatFormatting.DARK_RED), true);
                cir.setReturnValue(InteractionResultHolder.fail(stack));
                return;
            }
        } else if (stack.getMaxDamage() > 0 && stack.getDamageValue() >= stack.getMaxDamage()) {
            cir.setReturnValue(InteractionResultHolder.fail(stack));
            return;
        }
        user.startUsingItem(hand);
        cir.setReturnValue(InteractionResultHolder.consume(stack));
    }
}
