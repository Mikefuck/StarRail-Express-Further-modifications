package com.habitrain.core.api.spi;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * 上游「额外槽位」回收桥接接口（SPI）。
 *
 * <p>{@link com.habitrain.core.api.ItemReclaimHelper} 在任务取消时要回收主背包 / 盔甲 /
 * 副手 / 鼠标携带之外的<b>上游 SRE 额外槽位</b>（{@code ExtraSlotComponent}）。
 * 该组件属上游实现，公开层不应编译期依赖它，因此改为接口注入。
 *
 * <p>未注册实现时视为「没有额外槽位」，回收退化为无操作（不影响主背包路径）。
 */
public interface ExtraSlotReclaimBridge {

    /**
     * 移除该玩家额外槽位中所有匹配 {@code match} 的物品。
     *
     * @return {@code true} 表示确实移除了至少一件物品
     */
    boolean reclaimMatching(Player player, Predicate<ItemStack> match);
}
