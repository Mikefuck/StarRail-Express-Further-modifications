package com.habitrain.core.api.role.v2.client;

import com.habitrain.core.api.spi.RoleSpi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.function.Consumer;

/** Public, physical-client entry point for registered stock role screens. */
@Environment(EnvType.CLIENT)
public interface RoleScreenClientApi {
    static RoleScreenClientApi instance() {
        if (RoleSpi.screenClientOrNull() instanceof RoleScreenClientApi api) return api;
        throw new IllegalStateException("RoleScreenClientApi is unavailable before client initialization");
    }

    /**
     * Opens an enabled screen for the local player's current role, on the client
     * thread. PLAYER_PICK options require UUIDs; an empty list captures players
     * currently visible in this world. LIST uses supplied options; CONFIRM requires
     * none. IDs must be unique. Invalid options throw before opening. Returns false
     * if no player/role/enabled declaration exists (no callback in that case).
     * Callback runs once after selection, confirmation or cancellation, including
     * Escape, replacement and disconnect. Callback failures are logged and isolated.
     * Selection is a UI result: use RoleActionClientApi for server-validated actions.
     */
    boolean open(ResourceLocation screenId, List<RoleScreenOption> options, Consumer<RoleScreenResult> callback);

    default boolean open(ResourceLocation screenId, Consumer<RoleScreenResult> callback) {
        return open(screenId, List.of(), callback);
    }
}
