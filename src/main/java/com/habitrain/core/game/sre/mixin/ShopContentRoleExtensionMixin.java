package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.RoleOverlayAccessor;
import com.habitrain.core.role.state.RuntimeRoleServer;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.game.ShopContent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Objects;

/** Applies MODIFY transforms after SRE has resolved the complete shop list. */
@Mixin(value = ShopContent.class, remap = false)
public class ShopContentRoleExtensionMixin {

    @Inject(method = "getShopEntries", at = @At("RETURN"), cancellable = true)
    private static void habitrain$transformResolvedShop(
            ResourceLocation roleId,
            CallbackInfoReturnable<List<ShopEntry>> cir) {
        if (roleId == null) return;
        SRERole raw = TMMRoles.getRole(roleId);
        SRERole role = com.habitrain.core.game.sre.role.SreRoleResolver.resolveV2(roleId, raw);
        if (role == null) role = raw;
        if (role == null) return;
        CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(role);
        List<ShopEntry> baseline;
        if (role != raw) {
            List<ShopEntry> replEntries = role.getShopEntries();
            baseline = replEntries == null ? List.of() : List.copyOf(replEntries);
            if (overlay == null || overlay.shopTransform() == null) {
                cir.setReturnValue(baseline);
                return;
            }
        } else {
            if (overlay == null || overlay.shopTransform() == null) return;
            baseline = cir.getReturnValue() == null
                    ? List.of() : List.copyOf(cir.getReturnValue());
        }
        try {
            List<ShopEntry> transformed = overlay.shopTransform().transform(role, getServer(), baseline);
            cir.setReturnValue(List.copyOf(
                    Objects.requireNonNull(transformed, "v2 ShopTransform returned null")));
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("ShopContentRoleExtensionMixin")
                    .error("v2 ShopTransform failed for {}", roleId, t);
            if (role != raw) {
                cir.setReturnValue(baseline);
            }
        }
    }

    private static MinecraftServer getServer() {
        return RuntimeRoleServer.INSTANCE.server();
    }
}
