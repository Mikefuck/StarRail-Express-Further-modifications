package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.sre.CustomTaskTickGate;
import com.habitrain.core.task.TaskManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Core 进食任务活跃时允许满饥饿玩家完成任务，不影响大厅和普通世界。
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
        Player self = (Player) (Object) this;
        if (!(self.level() instanceof ServerLevel)) return;
        TaskInstance task = TaskManager.getInstance().getActiveTask(self.getUUID());
        if (task != null
                && HabiTrainCore.TASK_EAT.equals(task.getFullId())
                && !task.isFulfilled()
                && CustomTaskTickGate.allow(self)) {
            cir.setReturnValue(true);
        }
    }
}
