package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.task.TaskManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public class BlackoutEatMixin {

    @Inject(
            method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD")
    )
    private void habitrain$onEat(Level world, ItemStack stack, FoodProperties food,
                                 CallbackInfoReturnable<ItemStack> cir) {
        if (world.isClientSide()) return;
        if (!((Object) this instanceof ServerPlayer serverPlayer)) return;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null) return;

        if ("habitrain_core:blackout_eat".equals(task.getFullId())) {
            if (!task.isFulfilled() && task.getProgress() < task.getMaxProgress()) {
                task.setProgress(task.getMaxProgress());
            }
            return;
        }

        if ("habitrain_core:blackout_drink".equals(task.getFullId())) {
            if (!task.isFulfilled() && task.getProgress() < task.getMaxProgress()) {
                if (stack.getItem() instanceof PotionItem || stack.getItem() instanceof HoneyBottleItem) {
                    task.setProgress(task.getMaxProgress());
                }
            }
        }
    }
}