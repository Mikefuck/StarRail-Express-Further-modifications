package com.habitrain.core.api.spi;

import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端菜单门控桥接接口（SPI）。
 *
 * <p>对应实现 {@code config.MenuGateService}（静态方法，故由一个薄适配器
 * {@code config.MenuGateBridgeImpl} 装配）。去掉 {@code api.menu → config}
 * 反向依赖（审核 A2）。
 */
public interface MenuGateBridge {

    /** 门控总开关是否打开（与服务端是否为专用服务器无关）。 */
    default boolean isEnabled() {
        return false;
    }

    /**
     * 名单查询：该玩家是否在允许名单里。
     *
     * <p><b>这不是门禁判定</b>——专用服务器 + 门控关闭时它仍可能返回 {@code false}。
     * 门禁判定请用 {@link #isBlocked(ServerPlayer)}。
     */
    default boolean isAllowed(ServerPlayer player) {
        return false;
    }

    /**
     * 门禁判定：该玩家是否必须被拒绝菜单/配置写入。
     * 非专用服务器、门控关闭、或玩家为 {@code null} → {@code false}。
     */
    default boolean isBlocked(ServerPlayer player) {
        return false;
    }
}
