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
     *
     * <p>API-TV-02：当 {@code CoreSpi.isMenuGateInstalled()} 为 {@code false} 或底层桥接抛异常时，
     * 在专用服务端（或无法确认服务端非专用服）上保守返回 {@code true}（fail-closed）。</p>
     */
    public static boolean isBlocked(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = resolveServer(player);
        if (server == null) return true;
        if (!server.isDedicatedServer()) return false;
        if (!CoreSpi.isMenuGateInstalled()) return true;
        try {
            return CoreSpi.menuGate().isBlocked(player)
                    || CoreSpi.menuGate().isEnabled() && !CoreSpi.menuGate().isAllowed(player);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * {@code true} when the player must be denied menu/config writes on this server.
     *
     * <p>API-TV-02：若调用方传入 {@code server == null}，会主动尝试从 {@code player} 解析
     * {@code MinecraftServer}，并直接使用已解析的 {@code server.isDedicatedServer()} 结合
     * 桥接状态判定。</p>
     */
    public static boolean isBlocked(@Nullable ServerPlayer player, @Nullable MinecraftServer server) {
        if (player == null) {
            return false;
        }
        MinecraftServer actual = resolveServer(player);
        if (server != null && actual != null && server != actual) return true;
        MinecraftServer resolved = actual != null ? actual : server;
        if (resolved == null) {
            return isBlocked(player);
        }
        if (!resolved.isDedicatedServer()) {
            return false;
        }
        if (!CoreSpi.isMenuGateInstalled()) {
            return true;
        }
        try {
            return CoreSpi.menuGate().isBlocked(player)
                    || (CoreSpi.menuGate().isEnabled() && !CoreSpi.menuGate().isAllowed(player));
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 门控总开关是否打开。
     */
    public static boolean isEnabled() {
        if (!CoreSpi.isMenuGateInstalled()) return true;
        try {
            return CoreSpi.menuGate().isEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 名单查询：该玩家是否位于允许访问名单里。
     *
     * <p><b>这不是门禁判定</b>（审核 B20）：门禁判定请只用 {@link #isBlocked}。</p>
     */
    public static boolean isAllowed(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }
        try {
            return CoreSpi.menuGate().isAllowed(player);
        } catch (Throwable t) {
            return false;
        }
    }

    private static @Nullable MinecraftServer resolveServer(ServerPlayer player) {
        try {
            MinecraftServer s = player.getServer();
            if (s != null) {
                return s;
            }
            return player.level() != null ? player.level().getServer() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
