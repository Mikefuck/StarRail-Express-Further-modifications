package com.habitrain.core.betel;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class BetelFoodRestriction {
    private static final TagKey<net.minecraft.world.item.Item> BETEL_NUTS_TAG =
            TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("betel-nut-mod", "betel_nuts"));

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(
            (Player player, Level world, net.minecraft.world.InteractionHand hand) -> {
                if (world.isClientSide()) return InteractionResultHolder.pass(player.getItemInHand(hand));
                if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResultHolder.pass(player.getItemInHand(hand));

                var stack = serverPlayer.getItemInHand(hand);
                if (stack.isEmpty()) return InteractionResultHolder.pass(stack);

                var foodComponent = stack.get(DataComponents.FOOD);
                if (foodComponent == null) return InteractionResultHolder.pass(stack);

                UUID uuid = serverPlayer.getUUID();
                if (!BetelQuestState.hasFoodRestriction(uuid)) return InteractionResultHolder.pass(stack);

                if (stack.is(BETEL_NUTS_TAG)) {
                    return InteractionResultHolder.pass(stack);
                }

                serverPlayer.displayClientMessage(Component.literal("§c你的身体没办法接受正常的食物.."), true);
                return InteractionResultHolder.fail(stack);
            }
        );
        HabiTrainCore.LOGGER.info("已注册槟榔食物限制系统");
    }
}
