package com.habitrain.core.client.mixin;

import com.habitrain.core.client.cache.ActiveTaskCache;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 Mixin - 从同步数据中捕获自定义任务信息到 ActiveTaskCache
 * (移除了对旧 CustomTaskStore 的依赖)
 */
@Mixin(SREPlayerTaskComponent.class)
public class HudCustomTaskMixin {

    @Inject(method = "readFromSyncNbt", at = @At("TAIL"), remap = false)
    private void habitrain$onReadSyncNbt(CompoundTag tag, HolderLookup.Provider lookup, CallbackInfo ci) {
        // 同步数据不含 tasks 列表时（可能是部分同步），不动缓存，避免透视高亮闪烁。
        if (!tag.contains("tasks", Tag.TAG_LIST)) {
            return;
        }
        for (Tag element : tag.getList("tasks", Tag.TAG_COMPOUND)) {
            if (element instanceof CompoundTag compound && compound.contains("customId")) {
                String cid = compound.getString("customId");
                if (!cid.isEmpty()) {
                    ActiveTaskCache.setActiveTask(cid);
                    return;
                }
            }
        }
        // tasks 列表存在但无自定义任务条目 → 服务端确认玩家当前无自定义任务，清空缓存
        ActiveTaskCache.clear();
    }
}
