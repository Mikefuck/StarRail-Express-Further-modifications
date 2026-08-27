package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.game.ServerTaskInfoClasses;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only bridge to SRE's live selective-block reset percentage. */
@Mixin(value = ServerTaskInfoClasses.OnlySomeBlockResetTask.class, remap = false)
public interface OnlySomeBlockResetTaskAccessor {
    @Accessor("progress")
    int habitrain$getProgress();

    @Accessor("totalProgress")
    int habitrain$getTotalProgress();

    @Accessor("world")
    ServerLevel habitrain$getWorld();
}
