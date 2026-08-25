package com.habitrain.core.client.role;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.client.EliminatedRestPromptState;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.InstinctDecision;
import com.habitrain.core.api.role.v2.client.InstinctPhase;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.api.role.v2.client.RoleHudSpec;
import com.habitrain.core.api.role.v2.client.RoleHudWidget;
import com.habitrain.core.api.role.v2.client.RoleInstinctRule;
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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Physical-client adapter for v2 HUD / instinct declarations.
 *
 * <p>Never referenced from common / dedicated-server code.
 */
@Environment(EnvType.CLIENT)
public final class RoleClientExtensionHooks {

    private static boolean registered;
    private static String frozenRoundId;
    private static final Map<RoleKey, List<RoleHudSpec>> frozenHuds = new HashMap<>();
    private static final Map<RoleKey, List<RoleInstinctRule>> frozenInstincts = new HashMap<>();
    private static final Map<RoleKey, Collection<RoleHudWidget>> frozenWidgets = new HashMap<>();
    private static RoleKey hudCacheRole;
    private static String hudCacheLanguage;
    private static final Map<net.minecraft.resources.ResourceLocation, Component> HUD_TEXT_CACHE = new HashMap<>();

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
        com.habitrain.core.internal.CoreBootstrap.run(() -> {
            RoleClientExtensionApi.instance().loadProviders();
            RoleClientExtensionApi.instance().freeze();
        });
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
        if (EliminatedRestPromptState.isVisible() && phase != InstinctPhase.SPECTATOR) {
            return TrueFalseAndCustomResult.pass();
        }
        if (viewer == null || !(entity instanceof Player target)) {
            return TrueFalseAndCustomResult.pass();
        }
        RoleKey viewerRole = currentRole(viewer);
        RoleKey targetRole = currentRole(target);
        List<RoleInstinctRule> rules =
                viewerRole == null ? List.of() : instinctsForRound(viewerRole);
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
        if (player == null || mc.options.hideGui || EliminatedRestPromptState.isVisible()) {
            return;
        }
        RoleKey role = currentRole(player);
        if (role == null) {
            return;
        }
        String language = currentLanguageCode();
        if (!role.equals(hudCacheRole) || !java.util.Objects.equals(language, hudCacheLanguage)) {
            hudCacheRole = role;
            hudCacheLanguage = language;
            HUD_TEXT_CACHE.clear();
        }
        boolean spectator = player.isSpectator();
        for (RoleHudSpec spec : hudsForRound(role)) {
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
            Component text = HUD_TEXT_CACHE.get(spec.id());
            if (text == null) {
                String prefix = switch (spec.kind()) {
                    case ICON -> "[图标] ";
                    case PROGRESS -> "[进度] ";
                    case COOLDOWN -> "[冷却] ";
                    case CHARGE -> "[充能] ";
                    case TEXT, BADGE -> "";
                };
                text = Component.literal(prefix).append(Component.translatable(spec.textKey()));
                HUD_TEXT_CACHE.put(spec.id(), text);
            }
            graphics.drawString(mc.font, text, spec.x(), spec.y(), spec.color(), true);
        }
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        for (var widget : widgetsForRound(role)) {
            try {
                // The real render-frame tick delta (audit P1-5): animations and
                // interpolation must see the actual frame time, never a flat 0f.
                widget.render(width, height, tickDelta);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String currentLanguageCode() {
        try {
            var languages = Minecraft.getInstance().getLanguageManager();
            if (languages != null) {
                String selected = languages.getSelected();
                return selected == null ? "" : selected;
            }
        } catch (Throwable ignored) {
        }
        return "";
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
            try {
                return RoleCatalogApi.instance().canonicalize(role.identifier());
            } catch (Throwable ignored) {
                return RoleKey.of(role.identifier());
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<RoleHudSpec> hudsForRound(RoleKey role) {
        noteRoundSnapshot();
        if (frozenRoundId == null) {
            return RoleClientExtensionApi.instance().hudsFor(role);
        }
        return frozenHuds.computeIfAbsent(role, RoleClientExtensionApi.instance()::hudsFor);
    }

    private static List<RoleInstinctRule> instinctsForRound(RoleKey role) {
        noteRoundSnapshot();
        if (frozenRoundId == null) {
            return RoleClientExtensionApi.instance().instinctsFor(role);
        }
        return frozenInstincts.computeIfAbsent(role, RoleClientExtensionApi.instance()::instinctsFor);
    }

    private static Collection<RoleHudWidget> widgetsForRound(RoleKey role) {
        noteRoundSnapshot();
        if (frozenRoundId == null) {
            return RoleClientExtensionApi.instance().hudWidgetsFor(role);
        }
        return frozenWidgets.computeIfAbsent(role, RoleClientExtensionApi.instance()::hudWidgetsFor);
    }

    private static void noteRoundSnapshot() {
        String roundId = null;
        try {
            var payload = RoleSnapshotState.INSTANCE.get();
            if (payload != null) {
                roundId = payload.roundSnapshotId();
            }
        } catch (Throwable ignored) {
        }
        if (roundId == null || roundId.isBlank()) {
            if (frozenRoundId != null) {
                frozenRoundId = null;
                frozenHuds.clear();
                frozenInstincts.clear();
                frozenWidgets.clear();
                HUD_TEXT_CACHE.clear();
            }
            return;
        }
        if (!roundId.equals(frozenRoundId)) {
            frozenRoundId = roundId;
            frozenHuds.clear();
            frozenInstincts.clear();
            frozenWidgets.clear();
            HUD_TEXT_CACHE.clear();
        }
    }
}
