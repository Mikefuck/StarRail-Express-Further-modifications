package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.Map;

/**
 * Filters both server parsing and client suggestions at their shared registry
 * source. Replaced target IDs and inactive managed replacement IDs therefore
 * cannot be parsed even when they remain physically registered in TMMRoles.
 */
@Mixin(targets = "org.agmas.harpymodloader.commands.argument.RoleArgumentType", remap = false)
public abstract class RoleArgumentTypeMixin {
    @Redirect(
            method = {"parse", "listSuggestions"},
            at = @At(value = "INVOKE", target = "Ljava/util/Map;values()Ljava/util/Collection;")
    )
    private Collection<SRERole> habitrain$visibleCommandRoles(
            Map<ResourceLocation, SRERole> registry) {
        return SreRoleOverrideResolver.visibleRegistryRoles(registry.values());
    }
}
