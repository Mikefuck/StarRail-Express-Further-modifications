package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.RoleOverlayAccessor;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies MODIFY default items before Noellesroles' static INITIAL_ITEMS_MAP,
 * which otherwise masks SRERole#getDefaultItems for built-in roles.
 */
@Mixin(targets = "org.agmas.noellesroles.init.RoleInitialItems", remap = false)
public abstract class RoleInitialItemsMixin {
    @Inject(
            method = "getInitialItemsForRole(Lio/wifi/starrailexpress/api/SRERole;Lnet/minecraft/world/entity/player/Player;)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true,
            remap = true
    )
    private static void habitrain$patchedInitialItemsForPlayer(
            SRERole role, Player player, CallbackInfoReturnable<List<ItemStack>> cir) {
        List<ItemStack> patched = patchedItems(role, serverFor(player));
        if (patched != null) {
            cir.setReturnValue(copyItems(patched));
        }
    }

    @Inject(
            method = "getInitialItemsForRole(Lio/wifi/starrailexpress/api/SRERole;)Ljava/util/ArrayList;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$patchedInitialItemsForBook(
            SRERole role, CallbackInfoReturnable<ArrayList<ItemStack>> cir) {
        List<ItemStack> patched = patchedItems(role, serverFor(null));
        if (patched != null) {
            cir.setReturnValue(new ArrayList<>(copyItems(patched)));
        }
    }

    private static List<ItemStack> patchedItems(SRERole role, MinecraftServer server) {
        if (role == null || role.identifier() == null) return null;
        CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(role);
        if (overlay != null && overlay.defaultItemsPatch() != null) {
            try {
                return overlay.defaultItemsPatch().getDefaultItems(role, server);
            } catch (Throwable throwable) {
                com.habitrain.core.HabiTrainCore.LOGGER.warn(
                        "[RoleOverride] v2 default-items patch failed for {}", role.identifier(), throwable);
                return null;
            }
        }
        ModifyRoleDefinition def =
                RoleOverrideEngine.getInstance().getActiveModify(role.identifier());
        if (def == null || def.defaultItemsPatch().isEmpty()) return null;
        try {
            return def.defaultItemsPatch().get().getDefaultItems(role, server);
        } catch (Throwable throwable) {
            com.habitrain.core.HabiTrainCore.LOGGER.warn(
                    "[RoleOverride] default-items patch failed for {}", role.identifier(), throwable);
            return null;
        }
    }

    private static List<ItemStack> copyItems(List<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>();
        if (items == null) return result;
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                result.add(item.copy());
            }
        }
        return result;
    }

    private static MinecraftServer serverFor(Player player) {
        if (player != null && player.level() != null) {
            MinecraftServer server = player.level().getServer();
            if (server != null) return server;
        }
        Object gameInstance = FabricLoader.getInstance().getGameInstance();
        return gameInstance instanceof MinecraftServer server ? server : null;
    }
}
