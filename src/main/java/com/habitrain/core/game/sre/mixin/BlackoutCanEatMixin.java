package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.GameModeRegistry;
import io.wifi.starrailexpress.SRE;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 强制玩家在 SRE/停电模式游戏中可以进食，即使饥饿值已满。
 * 镜像 SRE 原版 PlayerEntityMixin.tmm$allowEatingRegardlessOfHunger（line 252-259）。
 * 不做此覆盖时，满饥饿下 Player.eat() 不会被调用，BlackoutEatMixin 永远不触发，
 * 导致 blackout_eat 任务在满饥饿时无法完成。
 */
@Mixin(Player.class)
public class BlackoutCanEatMixin {

    @Inject(
            method = "canEat(Z)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void habitrain$allowEatingRegardlessOfHunger(boolean ignoreHunger,
                                                         CallbackInfoReturnable<Boolean> cir) {
        // 与 SRE 原版一致：在 lobby（主城大厅）不强制；
        // 仅当本 mod 的停电模式对局进行中才强制 canEat=true，避免影响普通世界。
        if (SRE.isLobby) return;
        Player self = (Player) (Object) this;
        if (!(self.level() instanceof ServerLevel serverLevel)) return;
        var active = GameModeRegistry.getActiveForLevel(serverLevel);
        if (active.isEmpty() || !"habitrain:blackout".equals(active.get().getId())) return;
        if (active.get() instanceof com.habitrain.core.game.blackout.BlackoutMode bm
                && bm.isGameEnded(serverLevel)) return;
        cir.setReturnValue(true);
    }
}