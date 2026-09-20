package com.habitrain.core.api.client.menu;

import com.habitrain.core.api.spi.CoreSpi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Public client-side menu-gate query for addon config screens.
 *
 * <p><b>Client-only.</b> Dedicated servers must not call this. Addons should compile
 * against this type instead of reflecting {@code MenuAccessGuard}.
 *
 * <p>审核 B23：本类原位于 {@code api.menu}（公共包），而 {@code @Environment(CLIENT)}
 * 对非入口点没有加载期强制力——Fabric 不会阻止专用服务端解析到它，专用服一旦触碰即
 * {@code NoClassDefFoundError}。现在迁到 {@code api.client.menu}，与
 * {@code api.scene.client.SceneClientApi} 的组织方式一致。</p>
 */
@Environment(EnvType.CLIENT)
public final class MenuGateClientApi {
    private MenuGateClientApi() {}

    /**
     * {@code true} when the local player may open and edit gated Mod Menu pages.
     * Non-dedicated / gate-off / authorized → true.
     *
     * <p>专用服务器判定来自服务端下发的 {@code MenuGatePayload.dedicatedServer}，
     * 客户端不再用 {@code getSingleplayerServer() == null} 自行推断（审核 B19）。</p>
     */
    public static boolean isScreenAllowed() {
        return CoreSpi.menuGateClient().isScreenAllowed();
    }

    /** 服务端是否自报为专用服务器（与 {@link #isScreenAllowed()} 用的是同一份状态）。 */
    public static boolean isServerDedicated() {
        return CoreSpi.menuGateClient().isServerDedicated();
    }
}
