package com.habitrain.core.api;

import io.wifi.starrailexpress.cca.ExtraSlotComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务道具回收辅助类。
 *
 * 设计意图：
 *   任务系统给予玩家的物理道具（如 search_backpack 翻出的撬棍/手铐等），
 *   在任务因取消/隐藏而失效时应被回收，避免玩家无奖励获得强大道具。
 *
 * 机制：
 *   1. 发放时：给 ItemStack 打 CUSTOM_DATA NBT 标签 habitrain_grant = 任务 fullId
 *      （见 {@link #tagGrantedItem}）。不维护 TaskInstance 列表。
 *   2. 回收时：扫描主背包 + 盔甲 + 副手 + 鼠标携带 + ExtraSlot，移除所有带匹配标签的 ItemStack。
 *
 * 注意：成功完成的任务不回收（玩家保留道具作为奖励）。
 *       仅在取消/隐藏路径（init/clear/forceReplace/timeout/fail）调用 reclaim。
 */
public final class ItemReclaimHelper {

    /** NBT 标签 key，记录道具是哪个任务发放的 */
    public static final String GRANT_TAG_KEY = "habitrain_grant";

    private ItemReclaimHelper() {}

    /**
     * 给 ItemStack 打 habitrain_grant 标签。
     * 使用 CUSTOM_DATA 组件存 NBT（1.21+ 推荐方式，ItemStack 自身 NBT 已迁移至组件）。
     */
    public static ItemStack tagGrantedItem(ItemStack stack, String fullId) {
        if (stack == null || stack.isEmpty() || fullId == null) return stack;
        CompoundTag tag = stack.getOrDefault(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        tag.putString(GRANT_TAG_KEY, fullId);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        return stack;
    }

    /**
     * 打标后放入背包；装不下则掉落同一份已打标堆。
     */
    public static void giveTaggedItem(Player player, ItemStack stack, String fullId) {
        if (player == null || stack == null || stack.isEmpty()) return;
        ItemStack tagged = tagGrantedItem(stack, fullId);
        if (!player.getInventory().add(tagged)) {
            player.drop(tagged, false);
        }
    }

    /**
     * 回收主背包 + 盔甲 + 副手 + 鼠标携带 + ExtraSlot 中所有带匹配 habitrain_grant 标签的 ItemStack。
     */
    public static void reclaim(Player player, String fullId) {
        if (player == null || fullId == null) return;
        Inventory inv = player.getInventory();

        // 主背包（36格）
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (matchesGrant(stack, fullId)) {
                stack.setCount(0);
            }
        }
        // 装备栏（4格）
        for (int i = 0; i < inv.armor.size(); i++) {
            ItemStack stack = inv.armor.get(i);
            if (matchesGrant(stack, fullId)) {
                stack.setCount(0);
            }
        }
        // 副手
        for (int i = 0; i < inv.offhand.size(); i++) {
            ItemStack stack = inv.offhand.get(i);
            if (matchesGrant(stack, fullId)) {
                stack.setCount(0);
            }
        }
        boolean carriedChanged = reclaimMenuCarried(player.containerMenu, fullId);
        if (player.inventoryMenu != player.containerMenu) {
            carriedChanged |= reclaimMenuCarried(player.inventoryMenu, fullId);
        }
        reclaimExtraSlots(player, fullId);
        player.getInventory().setChanged();
        if (carriedChanged) {
            if (player.containerMenu != null) {
                player.containerMenu.broadcastChanges();
            }
            if (player.inventoryMenu != null && player.inventoryMenu != player.containerMenu) {
                player.inventoryMenu.broadcastChanges();
            }
        }
    }

    /** 1.21：鼠标携带栈在菜单上，不在 Inventory.carried。 */
    private static boolean reclaimMenuCarried(AbstractContainerMenu menu, String fullId) {
        if (menu == null) {
            return false;
        }
        ItemStack carried = menu.getCarried();
        if (!matchesGrant(carried, fullId)) {
            return false;
        }
        carried.setCount(0);
        menu.setCarried(ItemStack.EMPTY);
        return true;
    }

    private static void reclaimExtraSlots(Player player, String fullId) {
        try {
            ExtraSlotComponent.KEY.maybeGet(player).ifPresent(extra -> {
                if (extra.SLOTS == null || extra.SLOTS.isEmpty()) return;
                List<ResourceLocation> toRemove = new ArrayList<>();
                for (var entry : extra.SLOTS.entrySet()) {
                    if (matchesGrant(entry.getValue(), fullId)) {
                        toRemove.add(entry.getKey());
                    }
                }
                for (ResourceLocation slot : toRemove) {
                    extra.removeSlot(slot);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 检查 ItemStack 是否带匹配的 habitrain_grant 标签 */
    public static boolean matchesGrant(ItemStack stack, String fullId) {
        if (stack == null || stack.isEmpty()) return false;
        var customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (customData == null) return false;
        CompoundTag tag = customData.copyTag();
        return tag.contains(GRANT_TAG_KEY) && fullId.equals(tag.getString(GRANT_TAG_KEY));
    }

    /**
     * 便捷方法：在任务取消/隐藏路径调用。
     * 先 fire onReclaim（任务自定义清理），再扫描回收带标签的道具。
     */
    public static void reclaimForTask(Player player, TaskInstance task) {
        if (player == null || task == null) return;
        // 先让任务自定义回调处理（如清除效果等）
        task.getDefinition().onReclaim(player, task);
        // 再扫描回收物理道具（按 fullId 匹配 NBT 标签）
        reclaim(player, task.getFullId());
    }
}