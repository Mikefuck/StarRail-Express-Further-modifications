package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 Mixin — 原用于从同步数据中捕获自定义任务信息到 ActiveTaskCache。
 * <p>
 * 自 Batch 1 起，{@link com.habitrain.core.client.cache.ActiveTaskCache} 仅由
 * {@link com.habitrain.core.network.ActiveTaskPayload} 接收器
 * ({@link com.habitrain.core.client.HabiTrainCoreClient}) 写入，
 * NBT 同步路径不再写入缓存，以避免双写导致的状态不一致。
 * <p>
 * 此 Mixin 保留空壳便于后续扩展的 mixin 目标引用。
 */
@Mixin(SREPlayerTaskComponent.class)
public class HudCustomTaskMixin {

    @Inject(method = "readFromSyncNbt", at = @At("TAIL"), remap = false)
    private void habitrain$onReadSyncNbt(CompoundTag tag, HolderLookup.Provider lookup, CallbackInfo ci) {
        // 空壳：ActiveTaskCache 现在仅由 ActiveTaskPayload 接收器写入。
        // 保留方法签名以保持 mixin 引用稳定，不执行任何操作。
    }
}
