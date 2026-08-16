package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.GameEndTransitionCoordinator;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端对局结束确认 mixin。
 *
 * <p>{@code SREGameWorldComponent#setGameStatus(STOPPING)} 是所有对局结束路径的汇入点：
 * SRE 原版 {@code GameUtils.stopGame}（murder/repair/指令等全部汇入）与 api 侧
 * Blackout 的 5 个结束入口都经过它；且各方都在此之前写好了 SRE 结算组件
 * （winStatus + CustomWinner*），数据时序正确。在其 {@code RETURN} 通知
 * {@link GameEndTransitionCoordinator} 广播结束转场信号。</p>
 *
 * <p>仅拦截服务端 world（{@code ServerLevel}）：客户端本地状态机也会经过
 * {@code setGameStatus}（如 STOPPING→INACTIVE 的本地翻转），不得在客户端 world 上
 * 触发广播。单机（集成服务器）下服务端线程走的是 ServerLevel，广播正常。</p>
 */
@Mixin(value = SREGameWorldComponent.class, remap = false)
public abstract class SRESetGameStatusMixin {
    @Shadow
    @Final
    private Level world;

    @Inject(method = "setGameStatus", at = @At("RETURN"))
    private void habitrain$onGameStatusChanged(SREGameWorldComponent.GameStatus status,
                                               CallbackInfo ci) {
        try {
            if (world == null || !(world instanceof ServerLevel serverLevel)) {
                return;
            }
            if (status == SREGameWorldComponent.GameStatus.STOPPING) {
                GameEndTransitionCoordinator.onStatusStopping(serverLevel);
            } else {
                // STOPPING → INACTIVE（finalize 完成）等离开 STOPPING 的转换：释放广播标记。
                GameEndTransitionCoordinator.onStatusLeavingStopping(serverLevel);
            }
        } catch (Throwable t) {
            // 不因 mixin 失败阻断 SRE 状态机。
        }
    }
}
