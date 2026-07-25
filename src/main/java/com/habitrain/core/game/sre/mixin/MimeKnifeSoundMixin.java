package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoles;
import io.wifi.starrailexpress.content.item.KnifeItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 默剧杀手被动：取消举刀准备音效。
 * 通过在 use 开头对 mime 玩家取消播放路径——若无法精确取消 playSound，
 * 则在 HEAD 重写 use 为静默版过重；这里用 Redirect 更稳，先 Inject 后靠
 * 客户端/服务端 playSound 过滤。
 *
 * 实际策略：mixin KnifeItem.use，mime 时 cancel 整个 use 并手动开始使用动画无声音。
 */
@Mixin(value = KnifeItem.class, remap = false)
public class MimeKnifeSoundMixin {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void habitrain$silentRaise(Level world, Player user, net.minecraft.world.InteractionHand hand,
                                       CallbackInfoReturnable<net.minecraft.world.InteractionResultHolder<ItemStack>> cir) {
        if (user == null) return;
        if (!HabiRoles.isHabiRole(user, HabiRoles.MIME_KILLER)) return;
        ItemStack stack = user.getItemInHand(hand);
        user.startUsingItem(hand);
        cir.setReturnValue(net.minecraft.world.InteractionResultHolder.consume(stack));
    }
}
