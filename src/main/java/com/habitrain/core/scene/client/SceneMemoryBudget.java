package com.habitrain.core.scene.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按键记账的字节预算，附带最久未用（LRU）淘汰选取。
 *
 * <p>报告 §6.2 指出的问题：已解码的场景资产按 hash 存在一个 Map 里，<b>完全没有字节记账，
 * 也没有上限</b>——单个资产最大可以解到 128 MiB，几个背景加预览就能把堆吃掉相当一块。
 * 这里只做"记账 + 选出该淘汰谁"，真正的移除由持有 Map 的调用方执行，因为只有它知道
 * 哪些条目此刻正被使用。</p>
 *
 * <p>不依赖 Minecraft，也无后台线程：全部操作都是 O(条目数) 的纯内存计算。</p>
 */
public final class SceneMemoryBudget {
    private final Map<String, Long> bytesByKey = new HashMap<>();
    private final Map<String, Long> lastUsedSeq = new HashMap<>();
    private long quotaBytes;
    private long usedBytes;
    private long sequence;
    private long evictedTotal;
    private int overQuotaEvents;

    public SceneMemoryBudget(long quotaBytes) {
        this.quotaBytes = Math.max(0L, quotaBytes);
    }

    /** 登记或覆盖一个键的占用；覆盖时先减旧值再记新值，不会重复计数。 */
    public synchronized void put(String key, long bytes) {
        if (key == null) return;
        Long previous = bytesByKey.put(key, Math.max(0L, bytes));
        if (previous != null) usedBytes -= previous;
        usedBytes += Math.max(0L, bytes);
        lastUsedSeq.put(key, ++sequence);
    }

    public synchronized void remove(String key) {
        if (key == null) return;
        Long previous = bytesByKey.remove(key);
        if (previous != null) usedBytes -= previous;
        lastUsedSeq.remove(key);
    }

    /** 命中一次：刷新 LRU 次序。 */
    public synchronized void touch(String key) {
        if (key == null || !bytesByKey.containsKey(key)) return;
        lastUsedSeq.put(key, ++sequence);
    }

    public synchronized void clear() {
        bytesByKey.clear();
        lastUsedSeq.clear();
        usedBytes = 0L;
    }

    public synchronized boolean contains(String key) {
        return bytesByKey.containsKey(key);
    }

    public synchronized long usedBytes() {
        return usedBytes;
    }

    public synchronized int size() {
        return bytesByKey.size();
    }

    public synchronized long quotaBytes() {
        return quotaBytes;
    }

    public synchronized void setQuotaBytes(long quotaBytes) {
        this.quotaBytes = Math.max(0L, quotaBytes);
    }

    public synchronized boolean isOverQuota() {
        return usedBytes > quotaBytes;
    }

    public synchronized long evictedTotalBytes() {
        return evictedTotal;
    }

    /** 预算内无解的次数（剩下的全部被保护）：用于诊断，不做补偿。 */
    public synchronized int overQuotaEvents() {
        return overQuotaEvents;
    }

    /**
     * 选出应当淘汰的键，按最久未用升序。
     *
     * <p>{@code protectedKeys} 里的键<b>永不</b>出现在结果中——正在显示、正在构建或正在下载
     * 的资产一旦被丢掉，轻则整份重新解码，重则让持有引用的构建路径读到已被回收的数据。</p>
     *
     * @return 需要移除的键；调用方负责真正删除并回调 {@link #remove(String)}
     */
    public synchronized List<String> selectEvictions(Set<String> protectedKeys) {
        if (usedBytes <= quotaBytes) return List.of();

        List<String> candidates = new ArrayList<>(bytesByKey.size());
        for (String key : bytesByKey.keySet()) {
            if (protectedKeys != null && protectedKeys.contains(key)) continue;
            candidates.add(key);
        }
        candidates.sort((left, right) -> Long.compare(
                lastUsedSeq.getOrDefault(left, 0L), lastUsedSeq.getOrDefault(right, 0L)));

        List<String> victims = new ArrayList<>();
        long remaining = usedBytes;
        for (String key : candidates) {
            if (remaining <= quotaBytes) break;
            victims.add(key);
            remaining -= bytesByKey.getOrDefault(key, 0L);
        }
        if (remaining > quotaBytes) {
            overQuotaEvents++;
        }
        return victims;
    }

    /** 调用方在移除条目后回报释放的字节数（供诊断）。 */
    public synchronized void recordEvicted(long bytes) {
        if (bytes > 0L) evictedTotal += bytes;
    }
}
