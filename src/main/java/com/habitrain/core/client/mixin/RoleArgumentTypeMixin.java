package com.habitrain.core.client.mixin;

import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Mixin into SRE's RoleArgumentType to filter replaced roles from command suggestions.
 * When a player types a role ID in a command, replaced roles won't appear in suggestions.
 */
@Mixin(targets = "org.agmas.harpymodloader.commands.argument.RoleArgumentType", remap = false)
public class RoleArgumentTypeMixin {

    @Inject(method = "listSuggestions", at = @At("RETURN"), cancellable = true)
    private <S> void filterReplacedRoleSuggestions(
            com.mojang.brigadier.context.CommandContext<S> context,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder,
            CallbackInfoReturnable<java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>> cir
    ) {
        cir.getReturnValue().thenAccept(suggestions -> {
            List<com.mojang.brigadier.suggestion.Suggestion> filtered = new ArrayList<>();
            for (com.mojang.brigadier.suggestion.Suggestion suggestion : suggestions.getList()) {
                String text = suggestion.getText();
                ResourceLocation id = ResourceLocation.tryParse(text);
                if (id != null && RoleOverrideEngine.getInstance().isReplaced(id)) {
                    continue; // skip replaced roles
                }
                filtered.add(suggestion);
            }
            // Replace the suggestions list in the Suggestions object
            com.mojang.brigadier.suggestion.Suggestions filteredSuggestions = new com.mojang.brigadier.suggestion.Suggestions(
                suggestions.getRange(), filtered
            );
            cir.getReturnValue().complete(filteredSuggestions);
        });
    }
}
