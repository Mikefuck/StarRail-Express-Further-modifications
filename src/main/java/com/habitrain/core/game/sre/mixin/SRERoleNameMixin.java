package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.RoleOverlayAccessor;
import com.habitrain.core.role.state.RuntimeRoleServer;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the active v2 name patch to the original role object. */
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
    }

    private static MinecraftServer getServer() {
        return RuntimeRoleServer.INSTANCE.server();
    }
}
