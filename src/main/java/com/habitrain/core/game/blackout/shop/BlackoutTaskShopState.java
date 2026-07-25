package com.habitrain.core.game.blackout.shop;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 停电任务商店全局对局状态。
 * <p>
 * 每个 {@link ServerLevel#dimension()} 隔离一份状态，在 {@code onPreStart} 重置、{@code onCleanup} 清理。
 * <ul>
 *   <li>{@code generatorDestroyed} — 炸毁发电机是否完成（决定好人供电三件套隐藏 / 临时电源上架）</li>
 *   <li>{@code restoreUsed} — 本局是否已成功恢复供电一次（第二次永久停电不再派发恢复供电）</li>
 *   <li>{@code furnaceExplosionTaken} — 炸毁发电机任务是否已被接取/完成（全局只能一次）</li>
 *   <li>玩家维度 {@code tempPowerExpiry} — 临时电源提灯到期 gameTick</li>
 * </ul>
 */
public final class BlackoutTaskShopState {
    private static final Map<ResourceKey<Level>, RoundState> STATES = new ConcurrentHashMap<>();

    private BlackoutTaskShopState() {}

    private static final class RoundState {
        volatile boolean generatorDestroyed = false;
        volatile boolean restoreUsed = false;
        volatile boolean furnaceExplosionTaken = false;
        final Map<UUID, Long> tempPowerExpiry = new ConcurrentHashMap<>();
    }

    public static void reset(ServerLevel level) {
        STATES.put(level.dimension(), new RoundState());
    }

    public static void cleanup(ServerLevel level) {
        STATES.remove(level.dimension());
    }

    private static RoundState get(ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), k -> new RoundState());
    }

    public static boolean isGeneratorDestroyed(ServerLevel level) {
        var s = STATES.get(level.dimension());
        return s != null && s.generatorDestroyed;
    }

    public static void markGeneratorDestroyed(ServerLevel level) {
        get(level).generatorDestroyed = true;
    }

    public static boolean isRestoreUsed(ServerLevel level) {
        var s = STATES.get(level.dimension());
        return s != null && s.restoreUsed;
    }

    public static void markRestoreUsed(ServerLevel level) {
        get(level).restoreUsed = true;
    }

    public static boolean isFurnaceExplosionTaken(ServerLevel level) {
        var s = STATES.get(level.dimension());
        return s != null && s.furnaceExplosionTaken;
    }

    public static void markFurnaceExplosionTaken(ServerLevel level) {
        get(level).furnaceExplosionTaken = true;
    }

    /** 是否持有仍受本局状态跟踪的临时电源提灯。 */
    public static boolean hasTempPower(ServerLevel level, UUID uuid) {
        return get(level).tempPowerExpiry.containsKey(uuid);
    }

    public static void setTempPower(ServerLevel level, UUID uuid, long expiryTick) {
        get(level).tempPowerExpiry.put(uuid, expiryTick);
    }

    /** 返回所有临时电源到期条目，供 tick 检查（不拷贝）。 */
    public static Set<Map.Entry<UUID, Long>> tempPowerEntries(ServerLevel level) {
        return get(level).tempPowerExpiry.entrySet();
    }
}
