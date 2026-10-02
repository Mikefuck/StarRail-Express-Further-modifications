package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.MapResetProgressHud;
import io.wifi.starrailexpress.client.gui.LobbyPlayersRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「重置地图中」进度牌显示期间，暂停 SRE 大厅顶部的人数 / 参与状态 / 自动开始文字。
 *
 * <p>两者都画在屏幕上缘正中（SRE 文字 y≈5..55），而进度牌的几何不写深度，
 * 文字会直接压在牌面上。投票期间同一区域由投票面板（Screen）盖住，这里保持一致；
 * 进度牌收起后大厅文字照常恢复。</p>
 */
@Mixin(value = LobbyPlayersRenderer.class, remap = false)
public abstract class LobbyPlayersHudYieldMixin {
    @Inject(method = "renderHud", at = @At("HEAD"), cancellable = true)
    private static void habitrain$yieldToResetProgress(CallbackInfo ci) {
        if (MapResetProgressHud.isOccupyingTop()) {
            ci.cancel();
        }
    }
}
