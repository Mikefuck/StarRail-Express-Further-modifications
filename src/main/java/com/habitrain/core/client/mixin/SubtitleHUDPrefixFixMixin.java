package com.habitrain.core.client.mixin;

import com.habitrain.core.client.util.TaskTextNormalizer;
import net.exmo.sre.subtitle.client.SubtitleHUD;
import net.exmo.sre.subtitle.client.SubtitleHUD.SubtitleEntry;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubtitleHUD.class)
public abstract class SubtitleHUDPrefixFixMixin {
    @Shadow
    public abstract void enqueue(SubtitleEntry entry);

    @Inject(method = "enqueueFromPacket", at = @At("HEAD"), cancellable = true)
    private void habitrain$normalizeTaskTitle(Component mainText, Component subText, int durationTicks,
                                              int color, boolean typewriter, int screenPosition, CallbackInfo ci) {
        Component normalizedMain = TaskTextNormalizer.normalizeTaskComponent(mainText);
        this.enqueue(new SubtitleEntry(
                normalizedMain,
                subText,
                durationTicks,
                12,
                18,
                color,
                typewriter,
                screenPosition
        ));
        ci.cancel();
    }
}
