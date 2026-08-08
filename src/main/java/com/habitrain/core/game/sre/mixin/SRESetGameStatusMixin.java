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
 * 监听 SRE 游戏状态切换：进入 STOPPING 时触发结算画面广播，
 * 离开 STOPPING 时清理通知标记。
 */
@Mixin(value = SREGameWorldComponent.class, remap = false)
public abstract class SRESetGameStatusMixin {

    @Shadow @Final
    private Level world;

    @Inject(method = "setGameStatus", at = @At("HEAD"), remap = false, require = 0)
    private void habitrain$onGameStatusChanged(SREGameWorldComponent.GameStatus gameStatus, CallbackInfo ci) {
        if (world == null) return;
        if (!(world instanceof ServerLevel serverLevel)) return;
        try {
            if (gameStatus == SREGameWorldComponent.GameStatus.STOPPING) {
                GameEndTransitionCoordinator.onStatusStopping(serverLevel);
            } else {
                GameEndTransitionCoordinator.onStatusLeavingStopping(serverLevel);
            }
        } catch (Throwable t) {
            // 不打断 SRE 状态机
        }
    }
}
