package com.habitrain.core.client;

import io.wifi.starrailexpress.event.client.OnGameFinishedClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Client-side copy of the server-owned eliminated-player rest-area eligibility. */
@Environment(EnvType.CLIENT)
public final class EliminatedRestPromptState {
    private static boolean visible;
    private static boolean canToggle;
    private static boolean lifecycleRegistered;

    private EliminatedRestPromptState() {
    }

    public static boolean isVisible() {
        return visible;
    }

    public static boolean canToggle() {
        return canToggle;
    }

    public static void update(boolean visible, boolean canToggle) {
        EliminatedRestPromptState.visible = visible;
        EliminatedRestPromptState.canToggle = canToggle;
    }

    public static void clear() {
        update(false, false);
    }

    public static void registerLifecycle() {
        if (lifecycleRegistered) {
            return;
        }
        lifecycleRegistered = true;

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        OnGameFinishedClient.EVENT.register(EliminatedRestPromptState::clear);
    }
}
