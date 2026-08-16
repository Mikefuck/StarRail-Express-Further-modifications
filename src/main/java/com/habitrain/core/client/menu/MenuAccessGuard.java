package com.habitrain.core.client.menu;

import com.habitrain.core.network.MenuGatePayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 Mod 菜单访问门控状态（公共访问契约，供附属 mod 通过反射查询）。
 *
 * <p>规则：仅当联机连接专用服务器（非单机/局域网）且服务端门控开启时生效；
 * 当前玩家在允许列表内（UUID 或名字匹配）才可打开并编辑受门控的 Mod 菜单页面。
 * 其余场景一律放行。</p>
 *
 * <p>状态来自服务端 {@link MenuGatePayload}（玩家加入下发、命令变更后广播）。
 * 未收到任何同步前默认未启用门控，避免旧版服务端不发送该包时误锁页面。</p>
 */
@Environment(EnvType.CLIENT)
public final class MenuAccessGuard {
    private static volatile boolean enabled = false;
    private static final List<MenuGatePayload.Entry> ALLOWED = new ArrayList<>();

    private MenuAccessGuard() {}

    /** 应用服务端同步的门控状态（由 S2C 接收器在客户端线程调用）。 */
    public static void update(MenuGatePayload payload) {
        enabled = payload.isEnabled();
        ALLOWED.clear();
        ALLOWED.addAll(payload.getAllowed());
    }

    /** 换服/断线时清除上一台服务器下发的门控与白名单。 */
    public static void reset() {
        enabled = false;
        ALLOWED.clear();
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
        if (!enabled) return true;             // 服务端已关闭门控
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return true;
        String us = mc.player.getUUID().toString();
        String name = mc.player.getGameProfile().getName();
        for (MenuGatePayload.Entry e : ALLOWED) {
            if (e.uuid() != null && !e.uuid().isEmpty() && e.uuid().equalsIgnoreCase(us)) return true;
            if (e.name() != null && !e.name().isEmpty() && name != null && e.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}
