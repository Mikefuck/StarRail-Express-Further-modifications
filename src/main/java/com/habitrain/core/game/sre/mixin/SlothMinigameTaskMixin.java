package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import io.wifi.starrailexpress.cca.SREPlayerMinigameTaskComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rotation and parallel minigame refreshes share this final dispatch path. */
@Mixin(value = SREPlayerMinigameTaskComponent.class, remap = false)
public abstract class SlothMinigameTaskMixin {
    @Shadow private Player player;

    // Sabotage tasks have a separate refresh path from dispatchMinigameTask.
    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
    private void habitrain$pauseAllMinigames(CallbackInfo ci) {
        if (SlothComponent.KEY.get(player).hasForcedSleepTask()) ci.cancel();
    }

    @Inject(method = "dispatchMinigameTask", at = @At("HEAD"), cancellable = true)
    private void habitrain$finishSleepFirst(ServerPlayer player, ServerLevel level,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (SlothComponent.KEY.get(player).hasForcedSleepTask()) cir.setReturnValue(false);
    }
}
