package com.habitrain.core.client.menu;

import com.habitrain.core.api.spi.MenuGateClientBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 审核 A2：{@link MenuAccessGuard} 的方法是静态的，无法直接实现 SPI 接口方法，
 * 因此用这个薄适配器把静态实现装配到 {@link MenuGateClientBridge}。
 */
@Environment(EnvType.CLIENT)
public final class MenuGateClientBridgeImpl implements MenuGateClientBridge {

    public static final MenuGateClientBridgeImpl INSTANCE = new MenuGateClientBridgeImpl();

    private MenuGateClientBridgeImpl() {}

    @Override
    public boolean isScreenAllowed() {
        return MenuAccessGuard.isScreenAllowed();
    }

    @Override
    public boolean isServerDedicated() {
        return MenuAccessGuard.isServerDedicated();
    }
}
