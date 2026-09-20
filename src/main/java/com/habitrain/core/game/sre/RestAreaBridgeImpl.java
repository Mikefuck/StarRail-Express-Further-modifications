package com.habitrain.core.game.sre;

import com.habitrain.core.api.spi.RestAreaBridge;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@link RestAreaBridge} 的唯一实现：转发到 {@link EliminatedRestAreaService}。
 *
 * <p><b>审核 B-01</b>：休息区判定此前只存在于实现层，下游被迫越层 import 并用
 * {@code catch (Throwable)} 掩盖 {@code NoClassDefFoundError}，使门禁静默 fail-open。
 * 现在由本薄适配器把实现层能力注入公开层，下游改为依赖
 * {@code api.MatchRestStateApi}，核心重构时得到编译期错误而非运行期静默降级。
 *
 * <p>本类由 {@code internal.CoreSpiRegistrar} 在 bootstrap 期装配；装配失败时
 * {@code CoreSpi} 保持 NOOP（{@code isResting == true}，即保守判为休息中）。
 */
public final class RestAreaBridgeImpl implements RestAreaBridge {

    public static final RestAreaBridgeImpl INSTANCE = new RestAreaBridgeImpl();

    private RestAreaBridgeImpl() {}

    @Override
    public boolean isResting(ServerPlayer player) {
        return EliminatedRestAreaService.isResting(player);
    }
}
