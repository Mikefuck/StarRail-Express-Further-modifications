package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.task.TaskManager;
import io.wifi.starrailexpress.content.item.CocktailItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 Item.finishUsingItem HEAD，用于完成 blackout_drink 任务。
 * 镜像 SRE 原版 FoodItemMixin.bartenderVision（line 24-43）。
 *
 * 为什么需要这个 mixin：
 * 原版 Player.eat() 只对带 DataComponents.FOOD 的物品触发。
 * 药水（PotionItem）没有 FOOD 组件，永远走不到 Player.eat()，
 * 因此仅靠 BlackoutEatMixin 无法完成喝水任务。
 *
 * 此 mixin 拦截 finishUsingItem，对药水/蜂蜜瓶/鸡尾酒完成 blackout_drink 任务，
 * 与 BlackoutEatMixin 的 Player.eat 路径互补：
 *   - 食物（有 FOOD 组件）走 Player.eat → BlackoutEatMixin
 *   - 药水/蜂蜜瓶/鸡尾酒走 finishUsingItem → 本 mixin
 */
@Mixin(Item.class)
public class BlackoutDrinkItemMixin {

    @Inject(
            method = "finishUsingItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD")
    )
    public void habitrain$onFinishUsingItem(ItemStack stack, Level world, LivingEntity user,
                                             CallbackInfoReturnable<ItemStack> cir) {
        if (world.isClientSide()) return;
        if (!(user instanceof ServerPlayer serverPlayer)) return;

        // 仅当玩家当前活跃任务是 blackout_drink 且未完成时处理
        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null) return;
        if (!HabiTrainCore.TASK_BLACKOUT_DRINK.equals(task.getFullId())) return;
        if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) return;

        Item item = stack.getItem();
        // 与 SRE 原版一致：药水、蜂蜜瓶、鸡尾酒都算"喝"；额外包含牛奶桶
        if (item instanceof PotionItem || item instanceof HoneyBottleItem
                || item instanceof CocktailItem || item instanceof MilkBucketItem) {
            task.setProgress(task.getMaxProgress());
        }
    }
}