package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.VoteLaunchOverlayState;
import io.wifi.starrailexpress.client.util.MyBackgroundAmbience;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 防止开局同步的短暂中间状态误启 SRE 场景环境循环音。
 *
 * <p>SRE 的 {@link MyBackgroundAmbience} 会周期性尝试启动列车内外、风暴、马戏团等
 * 场景循环音。玩家模式、地图场景设置与传送位置分别同步到客户端，因此开局瞬间可能
 * 暂时出现“已进入对局且旧位置可见天空”的组合，外部列车声会被创建，随后再淡出。
 * 覆盖层激活及交还后的短宽限期内直接拒绝创建场景循环音；稳定后仍完全使用上游原有
 * 露天判定和淡入淡出逻辑，不改变游戏中的正常环境音行为。</p>
 */
@Mixin(value = MyBackgroundAmbience.class, remap = false)
public abstract class VoteLaunchAmbientSoundBlockMixin {
    @Inject(method = "tryStarting", at = @At("HEAD"), cancellable = true)
    private void habitrain$deferSceneAmbienceDuringLaunch(LocalPlayer player,
                                                          SoundManager soundManager,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (VoteLaunchOverlayState.isAmbientSoundBlockingNow()) {
            cir.setReturnValue(false);
        }
    }
}
