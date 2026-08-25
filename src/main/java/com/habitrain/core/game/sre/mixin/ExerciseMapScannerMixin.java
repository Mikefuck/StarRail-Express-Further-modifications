package com.habitrain.core.game.sre.mixin;

import net.minecraft.world.level.block.state.BlockState;
import org.agmas.noellesroles.utils.MapScanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MapScanner 扫描锻炼任务高亮点时，用枯萎珊瑚块族替换上游默认黑混凝土（instinct id 5）。
 * 黑混凝土不再高亮——完成判定（{@code ExerciseDeadCoralMixin}）同样只认珊瑚。
 * <p>上游 4.3.0 已将实际方块判断拆到 testTaskBlocksAndAddToGameUtils，
 * 因此 Redirect 必须指向该方法，否则扫描逻辑不会命中（require=0 会静默失效）。
 * {@code ordinal = 4} 只打 {@code BlockState.is(BLACK_CONCRETE)}，不劫持同方法内
 * 售货机 / 抽奖机 / 补给箱 / 音符盒 / 炉灶等其它 {@code is(Block)}。
 */
@Mixin(MapScanner.class)
public abstract class ExerciseMapScannerMixin {

    @Redirect(
            method = "testTaskBlocksAndAddToGameUtils",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z",
                    ordinal = 4
            ),
            require = 1
    )
    private static boolean habitrain$matchExerciseBlocks(BlockState state, net.minecraft.world.level.block.Block block) {
        return com.habitrain.core.game.sre.ExerciseTaskBlocks.matchesVanillaBlackConcreteCheck(state, block);
    }
}
