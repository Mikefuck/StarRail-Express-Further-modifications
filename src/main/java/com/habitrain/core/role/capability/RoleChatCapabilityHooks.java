package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.capability.ChatDecision;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityContext;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityStatus;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common-side chat gate for registered {@code RoleChatPolicy}s.
 *
 * <p>Uses Fabric {@link ServerMessageEvents} (always present) — not an
 * optional chat-mod class. Binds {@link RoleCapabilityKey#CHAT} as
 * {@code AVAILABLE} so {@code supports(CHAT)} is honest.
 */
public final class RoleChatCapabilityHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleChatCapability");
    private static boolean registered;

    private RoleChatCapabilityHooks() {}

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        RoleCapabilityApi.instance().bindAdapter(
                RoleCapabilityKey.CHAT, RoleCapabilityStatus.AVAILABLE);
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (sender == null) {
                return true;
            }
            RoleCapabilityContext ctx = RoleCapabilityContext.of(
                    sender.getUUID(), currentRole(sender), null, null);
            return RoleCapabilityApi.instance().evaluateChat(ctx) != ChatDecision.BLOCK;
        });
        LOGGER.info("Chat capability gate registered");
    }

    private static RoleKey currentRole(ServerPlayer player) {
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
