package com.habitrain.core.game.sre.role.sins.item;

import com.habitrain.core.game.sre.role.HabiRoleItems;
import io.wifi.starrailexpress.index.SREDataComponentTypes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 贪婪绑定收纳袋：原版 {@link Items#BUNDLE} + CUSTOM_DATA 标记 + {@link SREDataComponentTypes#OWNER}。
 * <p>
 * MVP：不注册新 Item；收集通过「袋 + 另一只手物品」右键吸收种类（见 {@code GreedComponent}）。
 */
public final class GreedPouchItem {
    public static final String TAG_GREED_POUCH = "habitrain_greed_pouch";
    public static final String TAG_GREED_OWNER = "habitrain_greed_owner";

    private GreedPouchItem() {}

    public static ItemStack createBoundPouch(Player owner) {
        ItemStack stack = new ItemStack(Items.BUNDLE, 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("贪婪收纳袋"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("绑定：不可丢弃；失袋即死"),
                Component.literal("主/副手持袋，另一手持物右键吸收种类")
        )));
        HabiRoleItems.putFlag(stack, TAG_GREED_POUCH, true);
        if (owner != null) {
            String id = owner.getUUID().toString();
            try {
                stack.set(SREDataComponentTypes.OWNER, id);
            } catch (Throwable ignored) {
            }
            CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            tag.putBoolean(TAG_GREED_POUCH, true);
            tag.putString(TAG_GREED_OWNER, id);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return stack;
    }

    public static boolean isGreedPouch(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (HabiRoleItems.hasFlag(stack, TAG_GREED_POUCH)) return true;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().getBoolean(TAG_GREED_POUCH)) return true;
        return false;
    }

    public static @Nullable UUID getOwnerUuid(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            if (stack.has(SREDataComponentTypes.OWNER)) {
                String owner = stack.get(SREDataComponentTypes.OWNER);
                UUID parsed = parseUuid(owner);
                if (parsed != null) return parsed;
            }
        } catch (Throwable ignored) {
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            if (tag.contains(TAG_GREED_OWNER)) {
                return parseUuid(tag.getString(TAG_GREED_OWNER));
            }
        }
        return null;
    }

    public static boolean isBoundPouchOf(@Nullable Player player, @Nullable ItemStack stack) {
        if (player == null || !isGreedPouch(stack)) return false;
        UUID owner = getOwnerUuid(stack);
        return owner != null && owner.equals(player.getUUID());
    }

    public static boolean playerHasOwnPouch(@Nullable Player player) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isBoundPouchOf(player, player.getInventory().getItem(i))) {
                return true;
            }
        }
        // Cursor during inventory open
        try {
            ItemStack cursor = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
            if (isBoundPouchOf(player, cursor)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static @Nullable UUID parseUuid(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
