package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Soft voicechat plugin: cancel microphone packets while sloth is sleeping.
 * Registered via fabric.mod.json {@code voicechat} entrypoint when the mod is present.
 */
public final class SlothVoicePlugin implements VoicechatPlugin {
    public static final String PLUGIN_ID = "habitrain_core_sloth";

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMic);
        HabiTrainCore.LOGGER.info("[Sloth] MicrophonePacketEvent listener registered");
    }

    private void onMic(MicrophonePacketEvent event) {
        try {
            VoicechatConnection sender = event.getSenderConnection();
            if (sender == null) return;
            de.maxhenkel.voicechat.api.ServerPlayer vcPlayer = sender.getPlayer();
            if (vcPlayer == null) return;
            Object entity = vcPlayer.getPlayer();
            if (!(entity instanceof ServerPlayer sp)) return;
            if (SlothComponent.isSleepingSloth(sp)) {
                event.cancel();
            }
        } catch (Throwable ignored) {
            // soft-fail: never break voice pipeline
        }
    }
}
