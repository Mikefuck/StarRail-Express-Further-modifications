package com.habitrain.taskapi.client.mixin;

import com.habitrain.taskapi.client.CustomTaskStore;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 Mixin - 从同步数据中捕获自定义任务信息到 CustomTaskStore
 */
@Mixin(SREPlayerTaskComponent.class)
public class HudCustomTaskMixin {

    @Inject(method = "readFromSyncNbt", at = @At("TAIL"), remap = false)
    private void habitrain$onReadSyncNbt(CompoundTag tag, HolderLookup.Provider lookup, CallbackInfo ci) {
        CustomTaskStore.clear();
        if (tag.contains("tasks", Tag.TAG_LIST)) {
            for (Tag element : tag.getList("tasks", Tag.TAG_COMPOUND)) {
                if (element instanceof CompoundTag compound && compound.contains("customId")) {
                    String cid = compound.getString("customId");
                    String cname = compound.getString("customName");
                    if (!cid.isEmpty()) {
                        CustomTaskStore.put(cid, cname.isEmpty() ? cid : cname);
                    }
                }
            }
        }
    }
}
