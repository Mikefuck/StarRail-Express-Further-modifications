package com.habitrain.core.client.mixin;

import com.habitrain.core.client.RepairModeClientState;
import com.habitrain.core.client.gui.VoteLaunchOverlayState;
import io.wifi.starrailexpress.client.util.MyBackgroundAmbientLoop;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 开局过渡或维修时终止旧场景循环音，并避免新实例在首次 tick 前满音量播放。 */
@Mixin(value = MyBackgroundAmbientLoop.class, remap = false)
public abstract class RepairAmbientLoopMixin extends AbstractTickableSoundInstance {
    protected RepairAmbientLoopMixin(SoundEvent sound, SoundSource source, RandomSource random) {
        super(sound, source, random);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void habitrain$startSilent(CallbackInfo ci) {
        this.volume = 0.0f;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    // tick 是 Minecraft 的映射方法，必须显式启用 remap。
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = true)
    private void habitrain$stopSceneAmbienceForRepairer(CallbackInfo ci) {
        if (RepairModeClientState.isLocalRepairer()
                || VoteLaunchOverlayState.isAmbientSoundBlockingNow()) {
            this.volume = 0.0f;
            stop();
            ci.cancel();
        }
    }
}
