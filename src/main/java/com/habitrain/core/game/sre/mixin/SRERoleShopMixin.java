package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.RoleOverlayAccessor;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin into SRERole to patch getShopEntries() when a MODIFY override is active.
 */
@Mixin(SRERole.class)
public class SRERoleShopMixin {

    @Inject(method = "getShopEntries", at = @At("HEAD"), cancellable = true)
    private void patchedShop(CallbackInfoReturnable<List<ShopEntry>> cir) {
        SRERole self = (SRERole) (Object) this;
        CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(self);
        if (overlay != null && overlay.shopPatch() != null) {
            MinecraftServer server = getServer();
            cir.setReturnValue(overlay.shopPatch().getShopEntries(self, server));
            return;
        }
        ModifyRoleDefinition def = RoleOverrideEngine.getInstance().getActiveModify(self.identifier());
        if (def != null && def.shopPatch().isPresent()) {
            MinecraftServer server = getServer();
            cir.setReturnValue(def.shopPatch().get().getShopEntries(self, server));
        }
    }

    private static MinecraftServer getServer() {
        Object gameInstance = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance();
        if (gameInstance instanceof MinecraftServer server) {
            return server;
        }
        return null;
    }
}
