package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 暴食：在 {@link Player#eat} 成功返回后叠正面效果。
 * 与 {@link BlackoutEatMixin}（HEAD 推进 blackout 吃/喝任务）并存，不改任务进度逻辑。
 */
@Mixin(Player.class)
public class GluttonyEatMixin {

    @Inject(
            method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN")
    )
    private void habitrain$gluttonyOnEat(Level world, ItemStack stack, FoodProperties food,
                                         CallbackInfoReturnable<ItemStack> cir) {
        if (world == null || world.isClientSide()) return;
        if (food == null) return;
        if (!((Object) this instanceof ServerPlayer serverPlayer)) return;
        if (serverPlayer.isSpectator() || !serverPlayer.isAlive()) return;
        GluttonyComponent.onSuccessfulEat(serverPlayer);
    }
}
