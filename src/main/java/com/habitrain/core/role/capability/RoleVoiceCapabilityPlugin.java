package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityKey;
import com.habitrain.core.api.role.v2.capability.VoiceDecision;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;

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

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        // voicechat 可能先于 core 初始化：此时 RoleSpi 尚未装配，不能触碰 RoleCapabilityApi。
        RoleVoiceAdapterBinding.requestFromVoicechat();
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
            java.util.UUID speaker = idOf(event.getSenderConnection());
            if (speaker == null) return;
            if (ServerVoicePermissions.current().evaluate(speaker, null, null, null) == VoiceDecision.BLOCK) {
                event.cancel();
            }
        } catch (Throwable t) {
            event.cancel();
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
            java.util.UUID from = idOf(sender), to = idOf(receiver);
            if (from == null || to == null) return false; // server-generated audio has no player policy
            return ServerVoicePermissions.current().evaluate(from, to, groupIdOf(sender), groupIdOf(receiver))
                    == VoiceDecision.BLOCK;
        } catch (Throwable t) {
            return true;
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

    private static java.util.UUID idOf(VoicechatConnection connection) {
        return connection == null || connection.getPlayer() == null ? null : connection.getPlayer().getUuid();
    }
}
