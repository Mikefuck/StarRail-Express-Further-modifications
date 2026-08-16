package com.habitrain.core.client;

import com.habitrain.core.network.EliminatedRestTogglePayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Uses the API's registered vote binding, rather than adding another key entry. */
@Environment(EnvType.CLIENT)
public final class EliminatedRestAreaKeyHandler {
    private EliminatedRestAreaKeyHandler() {
    }

    /**
     * Gives the shared vote key priority while the server says this player can
     * enter, or return from, the rest area.
     */
    public static boolean handleVoteKeyPress(Minecraft client) {
        if (!EliminatedRestPromptState.canToggle()
                || client.player == null
                || client.level == null
                || client.screen != null) {
            return false;
        }
        ClientPlayNetworking.send(new EliminatedRestTogglePayload());
        return true;
    }
}
