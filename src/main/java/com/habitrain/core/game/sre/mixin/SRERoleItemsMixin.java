package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.RoleOverlayAccessor;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin into SRERole to patch getDefaultItems() when a MODIFY override is active.
 */
@Mixin(SRERole.class)
public class SRERoleItemsMixin {

    @Inject(method = "getDefaultItems", at = @At("HEAD"), cancellable = true)
    private void patchedItems(CallbackInfoReturnable<List<ItemStack>> cir) {
        SRERole self = (SRERole) (Object) this;
        CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(self);
        if (overlay != null && overlay.defaultItemsPatch() != null) {
            MinecraftServer server = getServer();
            cir.setReturnValue(overlay.defaultItemsPatch().getDefaultItems(self, server));
            return;
        }
        ModifyRoleDefinition def = RoleOverrideEngine.getInstance().getActiveModify(self.identifier());
        if (def != null && def.defaultItemsPatch().isPresent()) {
            MinecraftServer server = getServer();
            cir.setReturnValue(def.defaultItemsPatch().get().getDefaultItems(self, server));
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
