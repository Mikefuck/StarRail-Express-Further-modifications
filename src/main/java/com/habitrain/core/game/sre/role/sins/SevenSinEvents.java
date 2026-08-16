package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;

/**
 * Remaining non-role-model global event for the seven sins.
 *
 * <p>All combat / interaction / lifecycle behavior has migrated to
 * {@link SevenSinV2BehaviorHooks} (provider-scoped v2 hooks). The only
 * process-global listener left here is the sloth chat lock, because the v2
 * hook model has no chat callback yet.
 */
public final class SevenSinEvents {
    private SevenSinEvents() {}

    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;

        try {
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
                if (SlothComponent.isSleepingSloth(sender)) {
                    sender.displayClientMessage(
                            Component.translatable("message.habitrain_core.sin_sloth.chat_locked"),
                            true
                    );
                    return false;
                }
                return true;
            });
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[SevenSins] ServerMessageEvents.ALLOW_CHAT_MESSAGE unavailable", t);
        }
    }
}
