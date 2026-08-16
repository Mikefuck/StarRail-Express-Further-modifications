package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.api.role.v2.client.RoleNameRenderRule;
import com.habitrain.core.api.role.v2.client.RoleRenderPhase;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Physical-client adapter for v2 {@link RoleNameRenderRule} (audit P1-2 / P1-5).
 *
 * <p>Currently supports the {@link RoleRenderPhase#NAMEPLATE} phase: hides a
 * role's nameplate when the rule says {@code hide()}, and applies the rule
 * color by re-styling the vanilla nameplate component. The mixin in
 * {@code client.mixin.EntityRendererMixin} consumes this helper.
 */
@Environment(EnvType.CLIENT)
public final class RoleNameRenderHelper {

    private RoleNameRenderHelper() {}

    /** The first NAMEPLATE rule declared for the target player's role, if any. */
    public static @Nullable RoleNameRenderRule findNameplateRule(Player player) {
        if (player == null || player.level() == null || !player.level().isClientSide) {
            return null;
        }
        RoleKey role = RoleClientExtensionHooks.currentRole(player);
        if (role == null) {
            return null;
        }
        for (RoleNameRenderRule rule : RoleClientExtensionApi.instance().nameRendersFor(role)) {
            if (rule.phase() == RoleRenderPhase.NAMEPLATE) {
                return rule;
            }
        }
        return null;
    }

    /** Whether the rule wants the nameplate completely hidden. */
    public static boolean shouldHide(@Nullable RoleNameRenderRule rule) {
        return rule != null && rule.hide();
    }

    /**
     * Applies the rule's color to the vanilla nameplate component. Returns the
     * original component when there is no color override.
     */
    public static Component applyColor(@Nullable RoleNameRenderRule rule, Component original) {
        if (rule == null || rule.color() == null || original == null) {
            return original;
        }
        MutableComponent copy = original.copy();
        return copy.setStyle(copy.getStyle().withColor(rule.color()));
    }
}
