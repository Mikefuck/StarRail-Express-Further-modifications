package com.habitrain.core.client.menu;

import com.habitrain.core.network.MenuGatePayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/**
 * 客户端 Mod 菜单访问门控状态。附属 mod 请调用
 * {@link com.habitrain.core.api.client.menu.MenuGateClientApi#isScreenAllowed()}，不要反射本类。
 *
 * <p>规则：仅当服务端<b>自报</b>为专用服务器且门控开启时生效；当前玩家由服务端判定
 * {@code youAreAllowed} 才可打开并编辑受门控的 Mod 菜单页面。其余场景一律放行。</p>
 *
 * <p>审核 B19/M-07：2.0.10 及以前这里用
 * {@code Minecraft.getConnection() != null && Minecraft.getSingleplayerServer() == null}
 * 自行推断「专用服务器」。在<b>局域网房主的整合服</b>上，客人客户端
 * {@code getSingleplayerServer() == null} 为真 → 被当成专用服 → 门控开启且不在名单 →
 * 整屏被锁，而服务端用 {@code MinecraftServer#isDedicatedServer()} 永远不会拒绝其写入。
 * 现在该标志由服务端随 {@link MenuGatePayload} 下发。</p>
 *
 * <p>状态来自服务端 {@link MenuGatePayload}（玩家加入下发、命令变更后按玩家广播）。
 * 未收到任何同步前默认未启用门控（{@code enabled=false}），避免旧版服务端不发送该包时误锁页面。</p>
 */
@Environment(EnvType.CLIENT)
public final class MenuAccessGuard {
    private static volatile boolean enabled = false;
    private static volatile boolean youAreAllowed = false;
    private static volatile boolean serverDedicated = false;

    private MenuAccessGuard() {}

    /** 应用服务端同步的门控状态（由 S2C 接收器在客户端线程调用）。 */
    public static void update(MenuGatePayload payload) {
        if (payload == null) {
            return;
        }
        enabled = payload.isEnabled();
        youAreAllowed = payload.youAreAllowed();
        serverDedicated = payload.isDedicatedServer();
    }

    /** 换服/断线时清除上一台服务器下发的门控状态。 */
    public static void reset() {
        enabled = false;
        youAreAllowed = false;
        serverDedicated = false;
    }

    /**
     * 服务端是否自报为专用服务器（门控仅在专用服务器生效）。
     *
     * <p>该值完全来自服务端下发，不再由客户端推断；未收到同步时为 {@code false}（放行）。</p>
     */
    public static boolean isServerDedicated() {
        return serverDedicated;
    }

    /** 当前玩家是否允许打开并编辑受门控的 Mod 菜单页面。 */
    public static boolean isScreenAllowed() {
        if (!serverDedicated) return true;     // 单机 / 局域网：功能关闭
        if (!enabled) return true;             // 服务端已关闭门控 / 尚未收到首包
        return youAreAllowed;
    }

    /** 仅用于诊断：当前客户端是否处于联机状态。 */
    public static boolean isConnected() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getConnection() != null;
    }
}
