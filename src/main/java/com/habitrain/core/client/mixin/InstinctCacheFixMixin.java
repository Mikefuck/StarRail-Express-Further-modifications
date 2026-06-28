package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.client.SREClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mixin - 修复 StarRailExpress 模组的 ConcurrentModificationException 崩溃
 *
 * 【问题分析】
 * SREClient.updateInstinctCache() 方法在迭代 cachedHighLightMap (HashMap) 的同时，
 * 在循环体内调用 cachedHighLightMap.put() 修改该 map，导致 Java 的 HashMap
 * 在迭代过程中检测到结构性修改，抛出 ConcurrentModificationException。
 *
 * 崩溃栈：
 *   HashMap$HashIterator.nextNode() ← 迭代器检测到 modCount 变化
 *   SREClient.updateInstinctCache() ← 在迭代中 put 了 cachedHighLightMap
 *
 * 【修复方式】
 * 将 entrySet() 返回的快照用新的 HashSet 包装，使 for-each 循环迭代的是
 * entrySet 的副本而非原始集合的实时视图。这样即使在循环中 put/remove 原始 map，
 * 迭代器也不会受到影响。
 */
@Mixin(SREClient.class)
public class InstinctCacheFixMixin {

    /**
     * 重定向 cachedHighLightMap.entrySet() 的调用
     * 将其返回值替换为 entrySet 的快照（副本）
     *
     * 这样 for-each 循环迭代的是快照，而非原始 Map 的实时视图。
     * 即使在循环内 cachedHighLightMap.put() 修改了原始 Map，
     * 迭代器也不会抛出 ConcurrentModificationException。
     */
    @Redirect(
            method = "updateInstinctCache",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;entrySet()Ljava/util/Set;"
            ),
            remap = false
    )
    private static Set<Map.Entry<UUID, Integer>> snapshotEntrySet(Map<UUID, Integer> map) {
        // 返回 entrySet 的副本 (HashSet)，避免迭代过程中原始 Map 被修改导致 CME
        return new HashSet<>(map.entrySet());
    }
}
