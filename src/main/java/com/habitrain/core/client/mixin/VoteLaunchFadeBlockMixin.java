package com.habitrain.core.client.mixin;

import com.habitrain.core.client.RepairModeClientState;
import com.habitrain.core.client.gui.GameEndOverlayState;
import com.habitrain.core.client.gui.VoteLaunchOverlayState;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 屏蔽 SRE 原版开局/对局结束黑场淡入淡出（仅客户端渲染侧）。
 *
 * <p>SRE 客户端在 {@code InGameHudMixin#tmm$removeSleepOverlayAndDoGameFade} 里读取
 * {@code SREGameWorldComponent#getFade()} 画全屏黑幕。开局转场或对局结束转场覆盖层
 * 激活（及宽限期）时，让 {@code getFade()} 返回 0，从而完全不绘制原版黑幕，
 * 改用我们自己的暗幕/扫场画面。</p>
 *
 * <p><b>单机关键约束：</b>{@code SREGameWorldComponent} 是客户端与服务端共享的 CCA 类，
 * 单机（integrated server）下同一份 class 被两个线程使用——服务端线程在
 * {@code tickCommon()} 用 {@code getFade() >= 60} 判定是否触发 {@code initializeGame}/
 * {@code finalizeGame}，客户端渲染线程用它画黑幕。若不加区分地在 {@code isBlockingNow()}
 * 时一律返回 0，单机下服务端线程读到的也是 0，开局/结束判定被卡死（开局 fade 永不达 60，
 * 游戏永远不开始，直到转场屏 100s fallback 释放覆盖层 fade 才恢复——这正是实机
 * 「原版 5 秒、本 mod 几十秒」的根因）。故本注入仅在 <b>客户端 world</b>
 * （{@code world.isClientSide()==true}）时才拦截；服务端 world 必须原样返回 fade，
 * 保证开局判定正常推进。</p>
 *
 * <p>维修员（{@link RepairModeClientState#isLocalRepairer()}）全程屏蔽 fade——维修员不参与
 * 对局，开局/结尾黑场都不应出现在其客户端。维修状态由服务端
 * {@link com.habitrain.core.network.RepairModeSyncPayload} 同步；该判断同样位于
 * {@code isClientSide()} 守卫之内，单机下不影响服务端线程的 fade 读取。</p>
 */
@Mixin(value = SREGameWorldComponent.class, remap = false)
public abstract class VoteLaunchFadeBlockMixin {
    @Shadow
    @Final
    private Level world;

    @Inject(method = "getFade", at = @At("HEAD"), cancellable = true)
    private void habitrain$suppressOriginalFade(CallbackInfoReturnable<Integer> cir) {
        // 仅拦截客户端渲染侧；服务端 world 的 fade 必须原样返回，否则单机开局/结束判定被卡死。
        if (world != null && world.isClientSide()
                && (RepairModeClientState.isLocalRepairer()
                        || VoteLaunchOverlayState.isBlockingNow()
                        || GameEndOverlayState.isBlockingNow())) {
            cir.setReturnValue(0);
        }
    }
}
