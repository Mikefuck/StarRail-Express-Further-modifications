package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.CustomTaskBlockScanner;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import net.minecraft.server.level.ServerLevel;
import org.agmas.noellesroles.utils.MapScanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MapScanner.class, remap = false)
public class MapScannerMixin {

    @Inject(method = "scanAllTaskBlocks", at = @At("RETURN"), remap = false)
    private static void habitrain$afterScanAllTaskBlocks(ServerLevel serverLevel, CallbackInfo ci) {
        if (serverLevel == null) {
            return;
        }
        AreasWorldComponent areas = AreasWorldComponent.KEY.get(serverLevel);
        CustomTaskBlockScanner.scan(serverLevel, areas);
    }
}
