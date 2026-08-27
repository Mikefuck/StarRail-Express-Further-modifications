package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.sre.ConsumableClassificationPolicy;
import com.habitrain.core.game.sre.CustomTaskTickGate;
import com.habitrain.core.game.sre.FoodDrinkConsumableClassifier;
import com.habitrain.core.task.TaskManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 ItemStack.finishUsingItem，确保所有真正完成饮用的物品都走同一条 Core 判定。
 * ItemStack 是统一调用点，不依赖具体饮品子类是否覆写 Item.finishUsingItem。
 */
@Mixin(ItemStack.class)
public class BlackoutDrinkItemMixin {

    @Inject(
            method = "finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD")
    )
    public void habitrain$onFinishUsingItem(Level world, LivingEntity user,
                                             CallbackInfoReturnable<ItemStack> cir) {
        if (world.isClientSide()) return;
        if (!(user instanceof ServerPlayer serverPlayer)) return;
        if (!CustomTaskTickGate.allow(serverPlayer)) return;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null) return;
        if (!HabiTrainCore.TASK_DRINK.equals(task.getFullId())) return;
        if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) return;

        ItemStack stack = (ItemStack) (Object) this;
        if (FoodDrinkConsumableClassifier.classify(stack)
                == ConsumableClassificationPolicy.Kind.DRINK) {
            task.setProgress(task.getMaxProgress());
        }
    }
}
