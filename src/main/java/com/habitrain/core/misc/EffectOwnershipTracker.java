package com.habitrain.core.misc;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 效果归属追踪器 —— 跨模组效果冲突解决方案
 *
 * ===== 问题背景 =====
 * Minecraft 原版效果系统没有「来源」概念。同一个效果类型(如 DARKNESS)在玩家身上只有一个槽位，
 * 后应用的会覆盖先应用的。removeStatusEffect(StatusEffects.DARKNESS) 会无条件移除 darkness，
 * 无法区分是关灯、槟榔戒断还是厨师食物添加的。
 *
 * ===== 设计方案 =====
 * 本追踪器采用「引用计数」机制。每个模组/系统在应用效果时调用 claim() 声明拥有权；
 * 在想要移除时调用 release() 释放拥有权。只有当所有声明者都释放后，系统才会真正移除该效果。
 *
 * 例1: 黑out + 槟榔戒断 都应用了 DARKNESS
 *   claim(blackout) + claim(betel_addiction)
 *   → release(blackout): 仍有 betel_addiction 在引用 → DARKNESS 保留
 *   → release(betel_addiction): 无引用 → DARKNESS 移除
 *
 * 例2: antiComaTick 过去无条件移除所有 DARKNESS（bug）
 *   现在：release("hecheng_tianxia", DARKNESS) → 还有其他来源吗？有→不移除；没有→移除
 *
 * ===== 来源命名规范 =====
 *   "blackout"           - SREWorldBlackoutComponent 关灯效果
 *   "betel_addiction"    - BetelNutAddictionComponent 槟榔戒断
 *   "hecheng_tianxia"    - HechengTianxiaEffects 合成天下槟榔
 *   "betel_quest"        - BetelQuestState 更多任务模组效果
 *   "chef_food"          - ChefFoodItem 厨师食物
 *   "handcuffs"          - HandCuffsItem 手铐
 *   "vegetarian"         - VegetarianFoodMixin 素食主义者
 */
public class EffectOwnershipTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("EffectTracker");

    // playerUUID → (effectRegistryName → source标签集合)
    private static final Map<UUID, Map<String, Set<String>>> ownership = new ConcurrentHashMap<>();

    private EffectOwnershipTracker() {}

    /**
     * 将效果类型转为注册名作为 Map 键（跨映射兼容）
     */
    private static String effectKey(Holder<MobEffect> effect) {
        return effect.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown:" + effect);
    }

    /**
     * 声明一个来源拥有某效果
     */
    public static void claim(UUID playerUuid, Holder<MobEffect> effect, String source) {
        String key = effectKey(effect);
        Set<String> sources = ownership
                .computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        sources.add(source);
    }

    /**
     * 释放一个来源对某效果的拥有权
     *
     * @return true 表示该效果不再被任何来源拥有，调用方应执行 removeEffect
     *         false 表示仍有其他来源在使用，不应移除效果
     */
    public static boolean release(UUID playerUuid, Holder<MobEffect> effect, String source) {
        Map<String, Set<String>> playerEffects = ownership.get(playerUuid);
        if (playerEffects == null) return false;

        String key = effectKey(effect);
        Set<String> sources = playerEffects.get(key);
        if (sources == null) return false;

        sources.remove(source);

        if (sources.isEmpty()) {
            playerEffects.remove(key);
            if (playerEffects.isEmpty()) {
                ownership.remove(playerUuid);
            }
            return true; // 可以安全移除该效果
        }
        return false; // 还有其他来源，不能移除
    }

    /**
     * 检查某来源是否声明了某效果
     */
    public static boolean isClaimedBy(UUID playerUuid, Holder<MobEffect> effect, String source) {
        Map<String, Set<String>> playerEffects = ownership.get(playerUuid);
        if (playerEffects == null) return false;
        Set<String> sources = playerEffects.get(effectKey(effect));
        return sources != null && sources.contains(source);
    }

    /**
     * 获取某效果的所有声明来源
     */
    public static Set<String> getSources(UUID playerUuid, Holder<MobEffect> effect) {
        Map<String, Set<String>> playerEffects = ownership.get(playerUuid);
        if (playerEffects == null) return Set.of();
        Set<String> sources = playerEffects.get(effectKey(effect));
        return sources == null ? Set.of() : Collections.unmodifiableSet(sources);
    }

    /**
     * 强制清理某来源的所有追踪数据（不检查引用计数）
     * 用于游戏结束/玩家重置时
     */
    public static void forceClean(UUID playerUuid, String source) {
        Map<String, Set<String>> playerEffects = ownership.get(playerUuid);
        if (playerEffects == null) return;

        playerEffects.values().forEach(s -> s.remove(source));
        playerEffects.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (playerEffects.isEmpty()) {
            ownership.remove(playerUuid);
        }
    }

    /**
     * 清理某玩家的所有追踪数据
     */
    public static void clearPlayer(UUID playerUuid) {
        ownership.remove(playerUuid);
    }

    /**
     * 获取跟踪统计信息（用于调试）
     */
    public static int getTrackedPlayerCount() {
        return ownership.size();
    }
}
