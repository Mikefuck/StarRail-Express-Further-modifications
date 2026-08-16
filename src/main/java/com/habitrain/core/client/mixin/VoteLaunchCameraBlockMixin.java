package com.habitrain.core.client.mixin;

import com.habitrain.core.client.RepairModeClientState;
import com.habitrain.core.client.gui.VoteLaunchOverlayState;
import net.exmo.sre.camera.AdvancedCameraSequence;
import net.exmo.sre.camera.client.AdvancedCameraDirector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 屏蔽 SRE 原版开局「由远及近到玩家」的相机拉近动画（{@code AdvancedCameraDirector}）。
 *
 * <p>开局转场覆盖层激活（及宽限期）时，忽略 {@code OnGameStarted} 触发的开局运镜，
 * 避免相机被导演抢走、与我们自己的转场画面叠加。宽限期覆盖 SRE 在 OnGameStarted 后
 * 立即发送开场镜头的时间窗，确保交还后不会残留一段镜头运镜。</p>
 *
 * <p>维修员全程屏蔽：其客户端没有开局转场覆盖层，若不拦截，相机 intro 会把维修员的
 * 视角强行拉走（维修状态由 {@link com.habitrain.core.network.RepairModeSyncPayload} 同步）。</p>
 */
@Mixin(value = AdvancedCameraDirector.class, remap = false)
public abstract class VoteLaunchCameraBlockMixin {
    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private static void habitrain$suppressOriginalCameraIntro(AdvancedCameraSequence sequence,
                                                              CallbackInfo ci) {
        if (VoteLaunchOverlayState.isBlockingNow() || RepairModeClientState.isLocalRepairer()) {
            ci.cancel();
        }
    }
}
