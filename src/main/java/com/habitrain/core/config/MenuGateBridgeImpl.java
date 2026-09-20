package com.habitrain.core.config;

import com.habitrain.core.api.spi.MenuGateBridge;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 审核 A2：{@link MenuGateService} 的方法是静态的，无法直接实现 SPI 接口方法，
 * 因此用这个薄适配器把静态实现装配到 {@link MenuGateBridge}。
 */
public final class MenuGateBridgeImpl implements MenuGateBridge {

    public static final MenuGateBridgeImpl INSTANCE = new MenuGateBridgeImpl();

    private MenuGateBridgeImpl() {}

    @Override
    public boolean isEnabled() {
        return MenuGateService.isEnabled();
    }

    @Override
    public boolean isAllowed(@Nullable ServerPlayer player) {
        return MenuGateService.isAllowed(player);
    }

    @Override
    public boolean isBlocked(@Nullable ServerPlayer player) {
        return MenuGateService.isBlocked(player);
    }
}
