package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 锻炼任务（ExerciseTask）目标方块：将上游默认的黑混凝土替换为枯萎珊瑚块族（全模式）。
 */
@Mixin(SREPlayerTaskComponent.ExerciseTask.class)
public abstract class ExerciseDeadCoralMixin {

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getBlock()Lnet/minecraft/world/level/block/Block;"
            ),
            require = 0
    )
    private Block habitrain$replaceWithDeadCoral(BlockState state) {
        return com.habitrain.core.game.sre.ExerciseTaskBlocks.remapForVanillaTick(state.getBlock());
    }
}
