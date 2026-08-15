package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityContext;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityStatus;
import com.habitrain.core.api.role.v2.capability.VoiceDecision;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional Simple Voice Chat adapter for v2 {@link RoleCapabilityApi}.
 *
 * <p>Loaded only through the {@code voicechat} entrypoint, so dedicated
 * servers and processes without the mod never see these classes. Binding
 * {@link RoleCapabilityKey#VOICE} as {@code AVAILABLE} is the only side
 * effect on the common capability registry.
 */
public final class RoleVoiceCapabilityPlugin implements VoicechatPlugin {

    public static final String PLUGIN_ID = "habitrain_core_role_voice";
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleVoiceCapability");

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        RoleCapabilityApi.instance().bindAdapter(
                RoleCapabilityKey.VOICE, RoleCapabilityStatus.AVAILABLE);
        LOGGER.info("Simple Voice Chat adapter bound");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMic);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::onLocational);
        registration.registerEvent(StaticSoundPacketEvent.class, this::onStatic);
        registration.registerEvent(EntitySoundPacketEvent.class, this::onEntity);
    }

    private void onMic(MicrophonePacketEvent event) {
        try {
            ServerPlayer speaker = playerOf(event.getSenderConnection());
            if (speaker == null) {
                return;
            }
            RoleCapabilityContext ctx = RoleCapabilityContext.of(
                    speaker.getUUID(), currentRole(speaker), null, null);
            if (RoleCapabilityApi.instance().evaluateVoice(ctx) == VoiceDecision.BLOCK) {
                event.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    private void onLocational(LocationalSoundPacketEvent event) {
        if (mustBlock(event.getSenderConnection(), event.getReceiverConnection())) {
            event.cancel();
        }
    }

    private void onStatic(StaticSoundPacketEvent event) {
        if (mustBlock(event.getSenderConnection(), event.getReceiverConnection())) {
            event.cancel();
        }
    }

    private void onEntity(EntitySoundPacketEvent event) {
        if (mustBlock(event.getSenderConnection(), event.getReceiverConnection())) {
            event.cancel();
        }
    }

    private static boolean mustBlock(VoicechatConnection sender, VoicechatConnection receiver) {
        try {
            ServerPlayer from = playerOf(sender);
            ServerPlayer to = playerOf(receiver);
            if (from == null || to == null) {
                return false;
            }
            // Audit P1-3: fill the real group ids and speaker→listener distance so
            // isolateGroup + hearWorld(false) can recognise true group members and
            // maxDistance is actually enforced.
            RoleCapabilityContext ctx = RoleCapabilityContext.of(
                    from.getUUID(), currentRole(from), to.getUUID(), currentRole(to),
                    groupIdOf(sender), groupIdOf(receiver), distanceBlocks(from, to));
            VoiceDecision decision = RoleCapabilityApi.instance().evaluateVoice(ctx);
            return decision == VoiceDecision.BLOCK;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The voicechat group id of a connection, or {@code null} for the world channel. */
    private static @org.jetbrains.annotations.Nullable java.util.UUID groupIdOf(VoicechatConnection connection) {
        try {
            de.maxhenkel.voicechat.api.Group group = connection.getGroup();
            return group == null ? null : group.getId();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Euclidean distance in blocks between two players, or {@code null} if unknown. */
    private static @org.jetbrains.annotations.Nullable Double distanceBlocks(ServerPlayer from, ServerPlayer to) {
        try {
            if (from == null || to == null || from.level() != to.level()) {
                return null;
            }
            return Math.sqrt(from.distanceToSqr(to));
        } catch (Throwable t) {
            return null;
        }
    }

    private static ServerPlayer playerOf(VoicechatConnection connection) {
        if (connection == null || connection.getPlayer() == null) {
            return null;
        }
        return connection.getPlayer().getPlayer() instanceof ServerPlayer player ? player : null;
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