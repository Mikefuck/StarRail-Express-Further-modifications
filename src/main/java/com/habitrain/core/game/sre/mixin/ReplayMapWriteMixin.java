package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class ReplayMapWriteMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void habitrain$preserveReplayGeometry(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> cir) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ServerLevel level) MatchReplayRecorder.beforeBlockWrite(level, chunk, pos);
    }
}
