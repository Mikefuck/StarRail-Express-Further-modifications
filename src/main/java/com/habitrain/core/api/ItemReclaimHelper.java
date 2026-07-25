package com.habitrain.core.api;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

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
 *   2. 回收时：扫描玩家背包 + 副手，移除所有带匹配标签的 ItemStack。
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
     * 回收玩家背包 + 副手中所有带匹配 habitrain_grant 标签的 ItemStack。
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
        player.getInventory().setChanged();
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