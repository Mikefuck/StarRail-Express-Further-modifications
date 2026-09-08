package com.habitrain.core.client.mixin;

import com.habitrain.core.client.RepairModeClientState;
import io.wifi.starrailexpress.client.util.MyBackgroundAmbientLoop;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 进入维修时终止已经启动的 SRE 场景循环音，启动端另行阻止重播。 */
@Mixin(value = MyBackgroundAmbientLoop.class, remap = false)
public abstract class RepairAmbientLoopMixin extends AbstractTickableSoundInstance {
    protected RepairAmbientLoopMixin(SoundEvent sound, SoundSource source, RandomSource random) {
        super(sound, source, random);
    }

    // tick 是 Minecraft 的映射方法，必须显式启用 remap。
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = true)
    private void habitrain$stopSceneAmbienceForRepairer(CallbackInfo ci) {
        if (RepairModeClientState.isLocalRepairer()) {
            this.volume = 0.0f;
            stop();
            ci.cancel();
        }
    }
}
