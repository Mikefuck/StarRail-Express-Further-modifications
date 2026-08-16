package com.habitrain.core.game.blackout;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 电话 / 任务商店 C2S 门闩：仅当玩家近期在对应方块处打开过 GUI，
 * 且仍在交互距离内时，才允许服务端执行雇警 / 购买。
 */
public final class BlackoutPhoneSessionGate {

    public enum Kind {
        HIRE,
        TASK_SHOP
    }

    /** 与原版方块交互大致相当的服务端校验距离（格）。 */
    public static final double MAX_RANGE = 8.0;
    /** 打开 GUI 后会话有效时长（tick）。 */
    public static final int MAX_AGE_TICKS = 20 * 120;

    private record Session(BlockPos pos, long openGameTime) {}

    private static final ConcurrentMap<UUID, Session> HIRE_SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Session> SHOP_SESSIONS = new ConcurrentHashMap<>();

    private BlackoutPhoneSessionGate() {}

    public static void markOpen(Kind kind, ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null || !(player.level() instanceof ServerLevel level)) return;
        map(kind).put(player.getUUID(), new Session(pos.immutable(), level.getGameTime()));
    }

    /**
     * @return null 表示通过；非 null 为拒绝原因
     */
    public static @Nullable String validate(Kind kind, ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) return "无效请求";
        Session session = map(kind).get(player.getUUID());
        if (session == null) {
            return kind == Kind.HIRE ? "请先使用路边电话" : "请先使用红色电话打开商店";
        }
        long age = level.getGameTime() - session.openGameTime();
        if (age < 0 || age > MAX_AGE_TICKS) {
            map(kind).remove(player.getUUID());
            return "电话会话已过期，请重新打开";
        }
        double distSq = player.distanceToSqr(
                session.pos().getX() + 0.5,
                session.pos().getY() + 0.5,
                session.pos().getZ() + 0.5);
        if (distSq > MAX_RANGE * MAX_RANGE) {
            return "距离电话过远";
        }
        return null;
    }

    /** 购买成功后刷新时间戳，允许多次购买同一会话。 */
    public static void touch(Kind kind, ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return;
        map(kind).computeIfPresent(player.getUUID(), (id, old) ->
                new Session(old.pos(), level.getGameTime()));
    }

    public static void clearPlayer(ServerPlayer player) {
        if (player == null) return;
        UUID id = player.getUUID();
        HIRE_SESSIONS.remove(id);
        SHOP_SESSIONS.remove(id);
    }

    public static void clearAll() {
        HIRE_SESSIONS.clear();
        SHOP_SESSIONS.clear();
    }

    private static ConcurrentMap<UUID, Session> map(Kind kind) {
        return kind == Kind.HIRE ? HIRE_SESSIONS : SHOP_SESSIONS;
    }
}
