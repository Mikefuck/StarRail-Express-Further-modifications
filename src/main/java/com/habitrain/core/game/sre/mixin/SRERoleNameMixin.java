package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.RoleOverlayAccessor;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into SRERole to patch getName() and getColor() when a MODIFY override is active.
 * If a ModifyRoleDefinition with namePatch/colorPatch is active for this role,
 * the patched values are returned instead of the original.
 */
@Mixin(SRERole.class)
public class SRERoleNameMixin {

    @Inject(method = "getName()Lnet/minecraft/network/chat/Component;", at = @At("HEAD"), cancellable = true)
    private void patchedName(CallbackInfoReturnable<Component> cir) {
        SRERole self = (SRERole) (Object) this;
        CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(self);
        if (overlay != null && overlay.namePatch() != null) {
            MinecraftServer server = getServer();
            cir.setReturnValue(overlay.namePatch().getName(self, server));
            return;
        }
        ModifyRoleDefinition def = RoleOverrideEngine.getInstance().getActiveModify(self.identifier());
        if (def != null && def.namePatch().isPresent()) {
            MinecraftServer server = getServer();
            cir.setReturnValue(def.namePatch().get().getName(self, server));
        }
    }

    @Inject(method = {"getColor()I", "color()I"}, at = @At("HEAD"), cancellable = true)
    private void patchedColor(CallbackInfoReturnable<Integer> cir) {
        SRERole self = (SRERole) (Object) this;
        ModifyRoleDefinition def = RoleOverrideEngine.getInstance().getActiveModify(self.identifier());
        if (def != null && def.colorPatch().isPresent()) {
            MinecraftServer server = getServer();
            cir.setReturnValue(def.colorPatch().get().getColor(self, server));
        }
    }

    private static MinecraftServer getServer() {
        net.fabricmc.loader.api.ModContainer container = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("habitrain_core").orElse(null);
        if (container == null) return null;
        Object gameInstance = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance();
        if (gameInstance instanceof MinecraftServer server) {
            return server;
        }
        return null;
    }
}
