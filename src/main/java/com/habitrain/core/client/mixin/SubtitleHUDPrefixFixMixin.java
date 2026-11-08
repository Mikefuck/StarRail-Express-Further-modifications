package com.habitrain.core.client.mixin;

import com.habitrain.core.client.util.TaskTextNormalizer;
import net.exmo.sre.subtitle.client.SubtitleHUD;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = SubtitleHUD.class, remap = false)
public abstract class SubtitleHUDPrefixFixMixin {

    @ModifyVariable(
            method = {
                    "enqueueFromPacket(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;IIZI)V",
                    "enqueueFromPacket(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;IIZIZ)V"
            },
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            remap = false
    )
    private Component habitrain$normalizeTaskTitle(Component mainText) {
        return TaskTextNormalizer.normalizeTaskComponent(mainText);
    }
}
