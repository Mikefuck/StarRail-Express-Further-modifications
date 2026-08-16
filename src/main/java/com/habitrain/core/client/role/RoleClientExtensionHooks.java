package com.habitrain.core.client.role;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.InstinctDecision;
import com.habitrain.core.api.role.v2.client.InstinctPhase;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.api.role.v2.client.RoleHudSpec;
import com.habitrain.core.role.client.InstinctRuleResolver;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.client.CommonInstinctEvents;
import io.wifi.starrailexpress.util.TrueFalseAndCustomResult;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Physical-client adapter for v2 HUD / instinct declarations.
 *
 * <p>Never referenced from common / dedicated-server code.
 */
@Environment(EnvType.CLIENT)
public final class RoleClientExtensionHooks {

    private static boolean registered;

    private RoleClientExtensionHooks() {}

    /** Whether the client-extension platform was loaded on this physical client. */
    public static boolean isLoaded() {
        return registered;
    }

    /**
     * Opens the stock v2 role screen for the local player's current role, if
     * any {@code RoleScreenSpec} is declared. Provider-specific trigger code
     * (commands, action callbacks, meeting UI) can call this entry point.
     */
    public static void openRoleScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        RoleScreenDispatcher.openForRole(currentRole(mc.player));
    }

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        RoleClientExtensionApi.instance().loadProviders();
        RoleClientExtensionApi.instance().freeze();
        CommonInstinctEvents.ALIVE_COMMON_BEFORE_EVENT.register(
                (self, target, enabled) -> apply(InstinctPhase.ALIVE_BEFORE, self, target));
        CommonInstinctEvents.ALIVE_COMMON_MIDDLE_EVENT.register(
                (self, target, enabled) -> apply(InstinctPhase.ALIVE_MIDDLE, self, target));
        CommonInstinctEvents.ALIVE_COMMON_AFTER_EVENT.register(
                (self, target, enabled) -> apply(InstinctPhase.ALIVE_AFTER, self, target));
        CommonInstinctEvents.SPECTATOR_COMMON_EVENT.register(
                (self, target, enabled) -> apply(InstinctPhase.SPECTATOR, self, target));
        HudRenderCallback.EVENT.register((graphics, deltaTracker) ->
                renderHud(graphics, deltaTracker.getGameTimeDeltaPartialTick(false)));
        HabiTrainCore.LOGGER.info("[RoleClientExtensionHooks] HUD/instinct adapters registered");
    }

    private static TrueFalseAndCustomResult<Integer> apply(InstinctPhase phase,
                                                           LocalPlayer viewer, Entity entity) {
        if (viewer == null || !(entity instanceof Player target)) {
            return TrueFalseAndCustomResult.pass();
        }
        RoleKey viewerRole = currentRole(viewer);
        RoleKey targetRole = currentRole(target);
        java.util.List<com.habitrain.core.api.role.v2.client.RoleInstinctRule> rules =
                viewerRole == null ? java.util.List.of()
                        : RoleClientExtensionApi.instance().instinctsFor(viewerRole);
        InstinctDecision decision = InstinctRuleResolver.resolve(
                rules, phase, viewerRole, targetRole);
        return switch (decision.kind()) {
            case PASS -> TrueFalseAndCustomResult.pass();
            case HIDE -> TrueFalseAndCustomResult.no();
            case CUSTOM -> TrueFalseAndCustomResult.custom(decision.color());
        };
    }

    private static void renderHud(GuiGraphics graphics, float tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }
        RoleKey role = currentRole(player);
        if (role == null) {
            return;
        }
        boolean spectator = player.isSpectator();
        for (RoleHudSpec spec : RoleClientExtensionApi.instance().hudsFor(role)) {
            if (spectator && !spec.showWhenSpectator()) {
                continue;
            }
            // Stock visual model: TEXT/BADGE render the translated string;
            // ICON/PROGRESS/COOLDOWN/CHARGE render a small kind marker plus the
            // translated string so experimental declarations are never silently
            // dropped. Providers that need real textures/values can use a
            // custom RoleHudWidget.
            if (spec.textKey().isEmpty()) {
                continue;
            }
            String prefix = switch (spec.kind()) {
                case ICON -> "[图标] ";
                case PROGRESS -> "[进度] ";
                case COOLDOWN -> "[冷却] ";
                case CHARGE -> "[充能] ";
                case TEXT, BADGE -> "";
            };
            graphics.drawString(mc.font,
                    Component.literal(prefix).append(Component.translatable(spec.textKey())),
                    spec.x(), spec.y(), spec.color(), true);
        }
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        for (var widget : RoleClientExtensionApi.instance().hudWidgetsFor(role)) {
            try {
                // The real render-frame tick delta (audit P1-5): animations and
                // interpolation must see the actual frame time, never a flat 0f.
                widget.render(width, height, tickDelta);
            } catch (Throwable ignored) {
            }
        }
    }

    public static RoleKey currentRole(Player player) {
        if (player == null || player.level() == null) {
            return null;
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.level());
            if (game == null) {
                return null;
            }
            SRERole role = game.getRole(player);
            if (role == null || role.identifier() == null) {
                return null;
            }
            return RoleKey.of(role.identifier());
        } catch (Throwable t) {
            return null;
        }
    }
}
