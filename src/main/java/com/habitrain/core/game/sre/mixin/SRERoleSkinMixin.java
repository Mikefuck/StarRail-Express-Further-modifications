package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.RoleSkinKind;
import com.habitrain.core.api.role.v2.client.RoleSkinSpec;
import com.habitrain.core.role.client.RoleClientExtensionRegistry;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies v2 {@link RoleSkinSpec} over {@code SRERole.getNormalSkin} /
 * {@code getPsychoSkin}. Common-side mixin: the registry types do not
 * load client classes.
 */
@Mixin(SRERole.class)
public class SRERoleSkinMixin {

    @Inject(method = "getNormalSkin", at = @At("HEAD"), cancellable = true, remap = false)
    private void habitrain$normalSkin(Player player, boolean isSlim,
                                      CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation texture = texture(RoleSkinKind.NORMAL, isSlim);
        if (texture != null) {
            cir.setReturnValue(texture);
        }
    }

    @Inject(method = "getPsychoSkin", at = @At("HEAD"), cancellable = true, remap = false)
    private void habitrain$psychoSkin(Player player, boolean isSlim,
                                      CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation texture = texture(RoleSkinKind.PSYCHO, isSlim);
        if (texture != null) {
            cir.setReturnValue(texture);
        }
    }

    private ResourceLocation texture(RoleSkinKind kind, boolean slim) {
        SRERole self = (SRERole) (Object) this;
        if (self.identifier() == null) {
            return null;
        }
        try {
            RoleClientExtensionRegistry registry =
                    (RoleClientExtensionRegistry) com.habitrain.core.api.role.v2.client.RoleClientExtensionApi.instance();
            RoleSkinSpec spec = registry.skinFor(RoleKey.of(self.identifier()), kind);
            return spec == null ? null : spec.texture(slim);
        } catch (Throwable t) {
            return null;
        }
    }
}
