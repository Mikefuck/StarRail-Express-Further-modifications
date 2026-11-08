package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoleItems;
import io.wifi.starrailexpress.api.ChargeableItem;
import io.wifi.starrailexpress.api.impl.KnifeChargeableItem;
import io.wifi.starrailexpress.content.item.KnifeItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.agmas.noellesroles.init.ModEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 卖花女被动：持有花束的玩家刀蓄力 +0.3s（+6 tick），同时作用于蓄力条 UI 与服务端/客户端释放判定。
 */
@Mixin(KnifeItem.class)
public abstract class BouquetChargeMixin extends Item implements ChargeableItem {

    private BouquetChargeMixin(Properties properties) {
        super(properties);
    }

    @Override
    public int getMaxChargeTime(ItemStack stack, Player player) {
        return habitrain$computeKnifeChargeTicks(player);
    }

    @Override
    public float getChargePercentage(ItemStack stack, Player player, int ticksUsingItem) {
        return Math.min((float) ticksUsingItem / getMaxChargeTime(stack, player), 1.0F);
    }

    @Override
    public float getMaxStamina(ItemStack stack, Player player) {
        return getMaxChargeTime(stack, player);
    }

    @Override
    public boolean hasSpecialVisualEffects(ItemStack stack, Player player) {
        return true;
    }

    @Override
    public void onFullyCharged(ItemStack stack, Player player) {
        new KnifeChargeableItem().onFullyCharged(stack, player);
    }

    @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
    private void habitrain$enforceBouquetChargeOnRelease(ItemStack stack, Level level, LivingEntity user,
                                                         int remainingUseTicks, CallbackInfo ci) {
        if (user instanceof Player player && HabiRoleItems.playerHasBouquet(player)) {
            int requiredTicks = habitrain$computeKnifeChargeTicks(player);
            if (this.getUseDuration(stack, user) - remainingUseTicks < requiredTicks) {
                ci.cancel();
            }
        }
    }

    @Unique
    private static int habitrain$computeKnifeChargeTicks(Player player) {
        int ticks = player != null && player.hasEffect(ModEffects.TWO_DIMENSIONAL_CAMERA) ? 4 : 8;
        if (player != null && HabiRoleItems.playerHasBouquet(player)) {
            ticks += 6;
        }
        return ticks;
    }
}
