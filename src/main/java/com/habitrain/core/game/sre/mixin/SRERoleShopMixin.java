package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.RoleOverlayAccessor;
import com.habitrain.core.role.state.RuntimeRoleServer;
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
        SRERole effective = com.habitrain.core.game.sre.role.SreRoleResolver.resolve(self);
        if (effective != null && effective != self) {
            cir.setReturnValue(effective.getShopEntries());
            return;
        }
        CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(self);
        if (overlay != null && overlay.shopPatch() != null) {
            MinecraftServer server = getServer();
            try {
                List<ShopEntry> entries = overlay.shopPatch().getShopEntries(self, server);
                if (entries != null) {
                    cir.setReturnValue(List.copyOf(entries));
                    return;
                }
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("SRERoleShopMixin")
                        .error("v2 ShopPatch failed for {}", self.identifier(), t);
            }
        }
    }

    private static MinecraftServer getServer() {
        return RuntimeRoleServer.INSTANCE.server();
    }
}
