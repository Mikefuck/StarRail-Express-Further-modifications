package com.habitrain.core.client.mixin;

import com.habitrain.core.client.cache.ClientMapIntroCache;
import io.wifi.starrailexpress.network.MapIntroSyncPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = MapIntroSyncPayload.class, remap = false)
public class MapIntroSyncPayloadMixin {

    @Inject(
            method = "<init>(Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;)V",
            at = @At("RETURN")
    )
    private void habitrain$cacheOnPayloadConstruct(List<?> maps, List<?> voteMaps, List<?> bagMaps,
                                                   List<?> policeMaps, List<?> underwaterMaps,
                                                   List<?> airMaps, List<?> trapMaps, List<?> horseMaps,
                                                   CallbackInfo ci) {
        ClientMapIntroCache.update((MapIntroSyncPayload) (Object) this);
    }
}
