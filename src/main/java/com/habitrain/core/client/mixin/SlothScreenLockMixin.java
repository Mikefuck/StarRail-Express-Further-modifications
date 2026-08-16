package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.GameEndOverlayState;
import com.habitrain.core.client.gui.GameEndTransitionScreen;
import com.habitrain.core.client.gui.VoteLaunchOverlayState;
import com.habitrain.core.client.gui.VoteLaunchTransitionScreen;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents a sleeping Sloth from opening gameplay, inventory, shop, or chat screens.
 * Also holds SRE's {@code CloseUiPayload} {@code setScreen(null)} while a
 * launch-transition or game-end-transition overlay is active, so the transition
 * is not dismissed early.
 */
@Mixin(Minecraft.class)
public abstract class SlothScreenLockMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void habitrain$lockSleepingSlothScreens(Screen screen, CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        // 开局/结束转场激活期间，拦截外部 setScreen(null)（SRE CloseUiPayload 的客户端实现），
        // 避免转场被原版「关闭界面」包提前结束。转场自身的 completeTransition 会先
        // scheduleGrace（active=false）再 setScreen(null)，不会被误拦。
        if (screen == null
                && ((client.screen instanceof VoteLaunchTransitionScreen && VoteLaunchOverlayState.isActive())
                || (client.screen instanceof GameEndTransitionScreen && GameEndOverlayState.isActive()))) {
            ci.cancel();
            return;
        }
        if (screen == null || client.player == null) return;
        // 结算/开局转场必须能覆盖沉睡中的懒惰玩家；否则动画会被本 Mixin 拦截。
        if (screen instanceof VoteLaunchTransitionScreen || screen instanceof GameEndTransitionScreen) return;
        if (!SlothComponent.isSleepingSloth(client.player)) return;

        String name = screen.getClass().getSimpleName();
        if ("PauseScreen".equals(name) || "DeathScreen".equals(name)
                || "ReceivingLevelScreen".equals(name)) {
            return;
        }
        ci.cancel();
    }
}
