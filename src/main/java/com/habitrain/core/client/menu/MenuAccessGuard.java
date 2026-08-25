package com.habitrain.core.client.menu;

import com.habitrain.core.network.MenuGatePayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/**
 * 客户端 Mod 菜单访问门控状态。附属 mod 请调用
 * {@link com.habitrain.core.api.menu.MenuGateClientApi#isScreenAllowed()}，不要反射本类。
 *
 * <p>规则：仅当联机连接专用服务器（非单机/局域网）且服务端门控开启时生效；
 * 当前玩家由服务端判定 {@code youAreAllowed} 才可打开并编辑受门控的 Mod 菜单页面。
 * 其余场景一律放行。</p>
 *
 * <p>状态来自服务端 {@link MenuGatePayload}（玩家加入下发、命令变更后按玩家广播）。
 * 未收到任何同步前默认未启用门控（{@code enabled=false}），避免旧版服务端不发送该包时误锁页面。</p>
 */
@Environment(EnvType.CLIENT)
public final class MenuAccessGuard {
    private static volatile boolean enabled = false;
    private static volatile boolean youAreAllowed = false;

    private MenuAccessGuard() {}

    /** 应用服务端同步的门控状态（由 S2C 接收器在客户端线程调用）。 */
    public static void update(MenuGatePayload payload) {
        enabled = payload.isEnabled();
        youAreAllowed = payload.youAreAllowed();
    }

    /** 换服/断线时清除上一台服务器下发的门控状态。 */
    public static void reset() {
        enabled = false;
        youAreAllowed = false;
    }

    /** 是否处于专用服务器联机场景（门控仅在专用服务器生效；单机/局域网返回 false）。 */
    public static boolean isDedicatedServer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;
        if (mc.getConnection() == null) return false;
        return mc.getSingleplayerServer() == null;
    }

    /** 当前玩家是否允许打开并编辑受门控的 Mod 菜单页面。 */
    public static boolean isScreenAllowed() {
        if (!isDedicatedServer()) return true; // 非专用服务器：功能关闭
        if (!enabled) return true;             // 服务端已关闭门控 / 尚未收到首包
        return youAreAllowed;
    }
}
