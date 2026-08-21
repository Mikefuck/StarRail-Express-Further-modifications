package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.client.SREClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mixin - 修复 StarRailExpress 模组的 ConcurrentModificationException 崩溃
 *
 * 【问题分析】
 * SREClient.cachedHighLightMap 默认为普通的 HashMap，在多线程渲染（如 Sodium、ImmediatelyFast 或渲染管线）
 * 与 updateInstinctCache() 迭代更新时，极易因并发读写或迭代中修改导致 ConcurrentModificationException。
 *
 * 【修复方式】
 * 1. 在 SREClient 类静态初始化（<clinit>）时，将 cachedHighLightMap 替换为线程安全的 ConcurrentHashMap。
 * 2. 在 updateInstinctCache 中安全重定向 entrySet()：对于 ConcurrentHashMap 直接使用其弱一致迭代器（天然防 CME 并发安全），
 *    非并发 Map 时加锁克隆快照。
 */
@Mixin(SREClient.class)
public class InstinctCacheFixMixin {

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void habitrain_core$replaceWithConcurrentHashMap(CallbackInfo ci) {
        if (!(SREClient.cachedHighLightMap instanceof ConcurrentHashMap)) {
            SREClient.cachedHighLightMap = new ConcurrentHashMap<>(SREClient.cachedHighLightMap != null ? SREClient.cachedHighLightMap : Map.of());
        }
    }

    /**
     * 重定向 cachedHighLightMap.entrySet() 的调用
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "updateInstinctCache",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;entrySet()Ljava/util/Set;",
                    ordinal = 0
            ),
            remap = false
    )
    private static Set snapshotEntrySet(Map map) {
        if (map instanceof ConcurrentHashMap) {
            return map.entrySet();
        }
        if (map == null) {
            return Set.of();
        }
        synchronized (map) {
            return new HashSet<>(map.entrySet());
        }
    }
}
