package com.habitrain.core.api.menu;

import com.habitrain.core.api.spi.CoreSpi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Public server-side menu-gate query for addons (lottery config C2S, commands).
 *
 * <p>Dedicated servers with the gate enabled block unauthorized players.
 * Single-player / LAN never blocks. Addons must call this at compile time;
 * do not reflect {@code MenuGateService}.
 */
public final class MenuGateApi {
    private MenuGateApi() {}

    /**
     * {@code true} when the player must be denied menu/config writes.
     * Null player, non-dedicated server, or a server that cannot be resolved
     * from the player → not blocked.
     *
     * <p>本方法直接委托实现层的完整判定（含 {@code player.level().getServer()} 兜底）。
     * 审核 M-20：旧实现在 {@code player.getServer() == null} 的瞬间会 fail-open，
     * 比内部实现更宽松。
     */
    public static boolean isBlocked(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return CoreSpi.menuGate().isBlocked(player);
    }

    /**
     * {@code true} when the player must be denied menu/config writes on this server.
     */
    public static boolean isBlocked(@Nullable ServerPlayer player, @Nullable MinecraftServer server) {
        if (server == null || !server.isDedicatedServer()) {
            return false;
        }
        if (player == null) {
            return false;
        }
        return CoreSpi.menuGate().isBlocked(player);
    }

    /**
     * 门控总开关是否打开。
     *
     * <p>它与「本服务器是否启用门控」无关：非专用服务器（单机 / 局域网）上即使本方法返回
     * {@code true}，{@link #isBlocked} 也永远返回 {@code false}。
     */
    public static boolean isEnabled() {
        return CoreSpi.menuGate().isEnabled();
    }

    /**
     * 名单查询：该玩家是否位于允许访问名单里。
     *
     * <p><b>这不是门禁判定</b>（审核 B20）：它<b>不</b>等于 {@link #isBlocked} 的取反。
     * 在专用服务器 + 门控已关闭时，未授权玩家会同时得到
     * {@code isBlocked() == false} 与 {@code isAllowed() == false}；{@code null} 玩家同样
     * 「既不拦截也不允许」。把本方法当门禁谓词用（{@code if (isAllowed(p)) 放行 else 拒绝}）
     * 会拒绝所有人——门禁判定请只用 {@link #isBlocked}。
     */
    public static boolean isAllowed(@Nullable ServerPlayer player) {
        return CoreSpi.menuGate().isAllowed(player);
    }
}
