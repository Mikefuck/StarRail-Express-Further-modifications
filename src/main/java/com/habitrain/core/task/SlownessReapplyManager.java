package com.habitrain.core.task;

import com.habitrain.core.misc.EffectOwnershipTracker;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 tick 重新施加缓慢，对抗 betel-nut-mod 等每 tick 清除效果的逻辑。
 * <p>
 * 条目在 {@code expireAtGameTime} 到期后自动 unregister 并按所有权安全移除缓慢，
 * 避免任务成功完成路径未走 onRemove 时永久 re-apply（如 repair_wiring）。
 */
public class SlownessReapplyManager {

    private static final String DEFAULT_SOURCE_KEY = "slowness_reapply";

    /**
     * @param amplifier       缓慢 amplifier（0=I）
     * @param duration        每次 re-apply 写入的效果时长（tick）
     * @param sourceTag       来源标识（调试与所有权追踪用）
     * @param expireAtGameTime 世界 gameTime 到期时刻（含）；到期后自动移除
     */
    public record EffectSpec(int amplifier, int duration, ResourceLocation sourceTag, long expireAtGameTime) {
        /** 兼容旧调用：用 duration 作为相对时长，需配合带 ServerLevel 的 register。 */
        public EffectSpec(int amplifier, int duration, ResourceLocation sourceTag) {
            this(amplifier, duration, sourceTag, Long.MAX_VALUE);
        }
    }

    private static final Map<ResourceKey<Level>, Map<UUID, EffectSpec>> activeEntries = new ConcurrentHashMap<>();
    private static boolean registered = false;

    private static String ownerKey(EffectSpec spec) {
        if (spec == null || spec.sourceTag() == null) {
            return DEFAULT_SOURCE_KEY;
        }
        return "reapply:" + spec.sourceTag();
    }

    /**
     * API-TV-16：仅当所有权全部释放且玩家当前缓慢等级不超过本规格时才剥除缓慢，
     * 避免误删其他职业/技能施加的更高等级缓慢效果。
     */
    private static void releaseAndRemoveSlowness(UUID playerId, ServerPlayer player, EffectSpec spec) {
        boolean canRemove = EffectOwnershipTracker.release(playerId, MobEffects.MOVEMENT_SLOWDOWN, ownerKey(spec));
        if (!canRemove || player == null || player.isRemoved()) {
            return;
        }
        MobEffectInstance existing = player.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        if (existing != null && spec != null && existing.getAmplifier() > spec.amplifier()) {
            return;
        }
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
    }

    public static void registerTickHandler() {
        if (registered) return;
        registered = true;
        ClearableHandlerRegistry.register(SlownessReapplyManager::clearAll);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeEntries.isEmpty()) return;
            for (var levelEntry : activeEntries.entrySet()) {
                ServerLevel level = server.getLevel(levelEntry.getKey());
                if (level == null) continue;
                Map<UUID, EffectSpec> levelMap = levelEntry.getValue();
                if (levelMap.isEmpty()) continue;
                long now = level.getGameTime();
                for (Iterator<Map.Entry<UUID, EffectSpec>> it = levelMap.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<UUID, EffectSpec> entry = it.next();
                    UUID playerId = entry.getKey();
                    ServerPlayer player = (ServerPlayer) level.getPlayerByUUID(playerId);
                    EffectSpec spec = entry.getValue();
                    if (now >= spec.expireAtGameTime()) {
                        it.remove();
                        releaseAndRemoveSlowness(playerId, player, spec);
                        continue;
                    }
                    if (player == null || player.isRemoved() || player.isDeadOrDying() || player.isSpectator()) {
                        it.remove();
                        releaseAndRemoveSlowness(playerId, player, spec);
                        continue;
                    }
                    // 已有足够剩余时长则跳过，减少每 tick new MobEffectInstance
                    MobEffectInstance existing = player.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    if (existing != null
                            && existing.getAmplifier() >= spec.amplifier()
                            && existing.getDuration() > 10) {
                        continue;
                    }
                    player.addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN, spec.duration(), spec.amplifier(),
                            false, true, true));
                }
            }
        });
    }

    /**
     * 注册缓慢 re-apply，并按 {@code durationTicks} 自动到期。
     *
     * @param level         用于读取 gameTime 与 dimension
     * @param playerId      玩家 UUID
     * @param amplifier     缓慢 amplifier
     * @param durationTicks 效果持续 tick（同时作为 re-apply 写入时长与到期相对时长）
     * @param sourceTag     来源
     */
    public static void register(ServerLevel level, UUID playerId, int amplifier, int durationTicks,
                                ResourceLocation sourceTag) {
        if (level == null || playerId == null) return;
        long expireAt = level.getGameTime() + Math.max(1, durationTicks);
        EffectSpec spec = new EffectSpec(amplifier, durationTicks, sourceTag, expireAt);
        register(level.dimension(), playerId, spec);
    }

    /**
     * 注册缓慢 re-apply（显式 expire）。
     */
    public static void register(ResourceKey<Level> levelKey, UUID playerId, EffectSpec spec) {
        if (levelKey == null || playerId == null || spec == null) return;
        EffectOwnershipTracker.claim(playerId, MobEffects.MOVEMENT_SLOWDOWN, ownerKey(spec));
        EffectSpec prev = activeEntries.computeIfAbsent(levelKey, k -> new ConcurrentHashMap<>()).put(playerId, spec);
        if (prev != null && !ownerKey(prev).equals(ownerKey(spec))) {
            EffectOwnershipTracker.release(playerId, MobEffects.MOVEMENT_SLOWDOWN, ownerKey(prev));
        }
    }

    public static void unregisterAllLevels(UUID playerId) {
        if (playerId == null) return;
        MinecraftServer server = GameLifecycleHandler.peekServer();
        ServerPlayer player = server != null && server.getPlayerList() != null
                ? server.getPlayerList().getPlayer(playerId)
                : null;
        unregisterAllLevels(playerId, player);
    }

    public static void unregisterAllLevels(UUID playerId, ServerPlayer player) {
        if (playerId == null) return;
        for (var levelMap : activeEntries.values()) {
            EffectSpec removed = levelMap.remove(playerId);
            if (removed != null) {
                releaseAndRemoveSlowness(playerId, player, removed);
            }
        }
    }

    /**
     * API-TV-16：局终清理全部条目时，同时为在线玩家安全移除残留的缓慢效果。
     */
    public static void clearAll() {
        MinecraftServer server = GameLifecycleHandler.peekServer();
        for (var levelMap : activeEntries.values()) {
            for (var entry : levelMap.entrySet()) {
                UUID playerId = entry.getKey();
                EffectSpec spec = entry.getValue();
                ServerPlayer player = server != null && server.getPlayerList() != null
                        ? server.getPlayerList().getPlayer(playerId)
                        : null;
                releaseAndRemoveSlowness(playerId, player, spec);
            }
        }
        activeEntries.clear();
    }
}
