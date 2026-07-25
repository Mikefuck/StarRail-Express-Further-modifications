package com.habitrain.core.game.sre.role;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * 用原版/上游物品 + CUSTOM_DATA 标记实现投稿道具，避免新物品注册。
 */
public final class HabiRoleItems {
    private HabiRoleItems() {}

    public static final String TAG_BOUQUET = "habitrain_bouquet";
    public static final String TAG_PEPPER_SPRAY = "habitrain_pepper_spray";
    public static final String TAG_MELEE_IMMUNE_UNTIL = "habitrain_melee_immune_until";
    /** 凶案替罪羊被动发放的假刀标记，回收时只删带此 tag 的刀。 */
    public static final String TAG_SCAPEGOAT_KNIFE = "habitrain_scapegoat_knife";

    public static final ResourceLocation ONCE_REVOLVER_ID = ResourceLocation.parse("noellesroles:once_revolver");
    public static final ResourceLocation THROWING_KNIFE_ID = ResourceLocation.parse("noellesroles:throwing_knife");
    public static final ResourceLocation SMOKE_GRENADE_ID = ResourceLocation.parse("noellesroles:smoke_grenade");
    public static final ResourceLocation FALLEN_LEAVES_ID = ResourceLocation.parse("yuushya:average_fallen_leaves");
    public static final ResourceLocation FAKE_KNIFE_ID = ResourceLocation.parse("noellesroles:fake_knife");

    public static ItemStack createBouquet(int count) {
        ItemStack stack = new ItemStack(Items.POPPY, Math.max(1, count));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("虞美人"));
        putFlag(stack, TAG_BOUQUET, true);
        return stack;
    }

    public static ItemStack createPepperSpray() {
        ItemStack stack = new ItemStack(Items.HONEY_BOTTLE, 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("防狼喷雾"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("使用后 10 秒内免疫近战击杀"),
                Component.literal("冷却 30 秒")
        )));
        putFlag(stack, TAG_PEPPER_SPRAY, true);
        return stack;
    }

    /** 凶案替罪羊被动：假刀（noellesroles:fake_knife）+ 回收标记。 */
    public static ItemStack createScapegoatKnife() {
        ItemStack stack = lookupItem(FAKE_KNIFE_ID, 1);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("假刀"));
        putFlag(stack, TAG_SCAPEGOAT_KNIFE, true);
        return stack;
    }

    public static boolean isScapegoatKnife(ItemStack stack) {
        return hasFlag(stack, TAG_SCAPEGOAT_KNIFE);
    }

    /** 收回替罪羊被动发放的假刀（只删带 tag 的）。 */
    public static void reclaimScapegoatKnives(Player player) {
        if (player == null) return;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isScapegoatKnife(stack)) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    public static boolean isBouquet(ItemStack stack) {
        return hasFlag(stack, TAG_BOUQUET);
    }

    public static boolean isPepperSpray(ItemStack stack) {
        return hasFlag(stack, TAG_PEPPER_SPRAY);
    }

    public static boolean playerHasBouquet(net.minecraft.world.entity.player.Player player) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isBouquet(player.getInventory().getItem(i))) return true;
        }
        return false;
    }

    public static boolean consumeBouquet(net.minecraft.world.entity.player.Player player) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isBouquet(stack)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    public static ItemStack lookupItem(ResourceLocation id, int count) {
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, Math.max(1, count));
    }

    public static void putFlag(ItemStack stack, String key, boolean value) {
        if (stack == null || stack.isEmpty()) return;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putBoolean(key, value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static boolean hasFlag(ItemStack stack, String key) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyTag().getBoolean(key);
    }
}
