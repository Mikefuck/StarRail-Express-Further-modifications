package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.game.ShopContent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.fabricmc.loader.api.FabricLoader;
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
public class ShopContentRoleOverrideMixin {

    @Inject(method = "getShopEntries", at = @At("RETURN"), cancellable = true)
    private static void habitrain$transformResolvedShop(
            ResourceLocation roleId,
            CallbackInfoReturnable<List<ShopEntry>> cir) {
        ModifyRoleDefinition definition =
                RoleOverrideEngine.getInstance().getActiveModify(roleId);
        if (definition == null || definition.shopTransform().isEmpty()) {
            return;
        }

        SRERole role = TMMRoles.getRole(roleId);
        if (role == null) {
            return;
        }
        List<ShopEntry> baseline =
                cir.getReturnValue() == null ? List.of() : List.copyOf(cir.getReturnValue());
        List<ShopEntry> transformed = definition.shopTransform().get()
                .transform(role, getServer(), baseline);
        cir.setReturnValue(List.copyOf(
                Objects.requireNonNull(transformed, "ShopTransform returned null")));
    }

    private static MinecraftServer getServer() {
        Object gameInstance = FabricLoader.getInstance().getGameInstance();
        return gameInstance instanceof MinecraftServer server ? server : null;
    }
}
