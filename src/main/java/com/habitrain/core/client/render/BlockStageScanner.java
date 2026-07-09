package com.habitrain.core.client.render;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 背包扫描，检查玩家是否持有煤炭 / 红石火把。
 *
 * 用于添煤任务（add_coal）和炸毁熔炉任务（furnace_explosion）的阶段判定：
 * - 无煤炭 → 渲染 COAL_BLOCK 位置；有煤炭 → 渲染 generator 位置
 * - 无红石火把 → 渲染 REDSTONE_TORCH 位置；有红石火把 → 渲染 TNT 位置
 *
 * 背包扫描每帧遍历 40+ 格会导致微卡顿，因此缓存结果 2 秒，
 * 之后才重新扫描。
 */
public final class BlockStageScanner {

    private static final long INVENTORY_CACHE_TTL_MS = 2000L;

    private static long lastCoalCheckTime = 0;
    private static boolean cachedHasCoal = false;
    private static long lastTorchCheckTime = 0;
    private static boolean cachedHasTorch = false;

    private BlockStageScanner() {}

    /**
     * 检查玩家背包中是否有煤炭。
     *
     * @param player 要检查的玩家
     * @return true 如果背包中有至少一个煤炭
     */
    public static boolean hasPlayerCoal(Player player) {
        if (player == null) return false;
        long now = System.currentTimeMillis();
        if (now - lastCoalCheckTime < INVENTORY_CACHE_TTL_MS) {
            return cachedHasCoal;
        }
        lastCoalCheckTime = now;
        cachedHasCoal = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.COAL)) {
                cachedHasCoal = true;
                break;
            }
        }
        return cachedHasCoal;
    }

    /**
     * 检查玩家背包中是否有红石火把。
     *
     * @param player 要检查的玩家
     * @return true 如果背包中有至少一个红石火把
     */
    public static boolean hasPlayerRedstoneTorch(Player player) {
        if (player == null) return false;
        long now = System.currentTimeMillis();
        if (now - lastTorchCheckTime < INVENTORY_CACHE_TTL_MS) {
            return cachedHasTorch;
        }
        lastTorchCheckTime = now;
        cachedHasTorch = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.REDSTONE_TORCH)) {
                cachedHasTorch = true;
                break;
            }
        }
        return cachedHasTorch;
    }
}
