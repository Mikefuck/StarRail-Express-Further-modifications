package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.sre.ConsumableClassificationPolicy;
import com.habitrain.core.game.sre.CustomTaskTickGate;
import com.habitrain.core.game.sre.FoodDrinkConsumableClassifier;
import com.habitrain.core.task.TaskManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
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
        if (!CustomTaskTickGate.allow(serverPlayer)) return;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null) return;

        ConsumableClassificationPolicy.Kind expected;
        if (HabiTrainCore.TASK_EAT.equals(task.getFullId())) {
            expected = ConsumableClassificationPolicy.Kind.EAT;
        } else if (HabiTrainCore.TASK_DRINK.equals(task.getFullId())) {
            expected = ConsumableClassificationPolicy.Kind.DRINK;
        } else {
            return;
        }
        if (!task.isFulfilled() && task.getProgress() < task.getMaxProgress()
                && FoodDrinkConsumableClassifier.classify(stack) == expected) {
            task.setProgress(task.getMaxProgress());
        }
    }
}
