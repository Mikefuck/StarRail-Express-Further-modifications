package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.CustomTaskBlockScanner;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import net.minecraft.server.level.ServerLevel;
import org.agmas.noellesroles.utils.MapScannerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * JSON cache hit / noReset never call {@code scanAllTaskBlocks}. Ensure the in-memory
 * custom-block snapshot belongs to the active map before broadcasting it to clients.
 */
@Mixin(value = MapScannerManager.class, remap = false)
public class MapScannerCacheBroadcastMixin {

    @Inject(method = "loadOrScanAndSaveScannerArea", at = @At("RETURN"), remap = false)
    private static void habitrain$broadcastCachedCustomBlocks(
            ServerLevel serverLevel, AreasWorldComponent areas, CallbackInfo ci) {
        if (serverLevel == null) {
            return;
        }
        CustomTaskBlockScanner.ensureCurrent(serverLevel, areas);
    }
}
