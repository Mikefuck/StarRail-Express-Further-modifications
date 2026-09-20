package com.habitrain.core.role.override;

import com.habitrain.core.api.spi.RoleVisibilityBridge;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import io.wifi.starrailexpress.api.SRERole;
import org.jetbrains.annotations.Nullable;

/**
 * {@link RoleVisibilityBridge} 的唯一实现：转发到 {@link SreRoleOverrideResolver}。
 *
 * <p><b>审核 M-01</b>：旋转选角的下游重实现假设「角色池是未过滤的原始池」，
 * 于是被隐藏 / 被替换的基线职业会重新出现在候选列表里。本适配器把核心唯一的
 * 可见性判定暴露给公开层，让下游用同一套语义过滤，而不是各自复制一份。
 *
 * <p>由 {@code internal.CoreSpiRegistrar} 在 bootstrap 期装配；装配失败时
 * {@code CoreSpi} 保持 NOOP（不过滤），候选池退化为未过滤而不会变空。
 */
public final class RoleVisibilityBridgeImpl implements RoleVisibilityBridge {

    public static final RoleVisibilityBridgeImpl INSTANCE = new RoleVisibilityBridgeImpl();

    private RoleVisibilityBridgeImpl() {}

    @Override
    public @Nullable SRERole resolve(@Nullable SRERole role) {
        return SreRoleOverrideResolver.resolve(role);
    }

    @Override
    public boolean isVisible(@Nullable SRERole role) {
        return SreRoleOverrideResolver.isVisible(role);
    }
}
