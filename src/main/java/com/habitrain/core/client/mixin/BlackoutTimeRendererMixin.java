package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.ClientBlackoutState;
import io.wifi.starrailexpress.client.gui.TimeRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin - 黑夜模式下屏蔽 SRE 原版的时间 HUD（{@link TimeRenderer}）。
 * <p>
 * 黑夜模式自带 {@link BlackoutHudOverlay} 顶部倒计时进度条，而 SRE 的
 * {@code TimeRenderer} 会为 {@code canSeeTime} 为 true 的角色（包括黑夜模式复用的
 * SRE 原版杀手/警察角色）再渲染一份原版时间显示，导致两套时间 HUD 重叠。
 * 这里在黑夜模式激活时直接取消 {@code renderHud}，避免重叠；非黑夜模式不受影响。
 */
@Mixin(TimeRenderer.class)
public class BlackoutTimeRendererMixin {

    @Inject(
            method = "renderHud",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$cancelDuringBlackout(Font font, LocalPlayer player,
                                                       io.wifi.utils.client.betterrender.FakeGuiGraphics g,
                                                       float partialTick, CallbackInfo ci) {
        if (ClientBlackoutState.isBlackoutModeActive()) {
            ci.cancel();
        }
    }
}