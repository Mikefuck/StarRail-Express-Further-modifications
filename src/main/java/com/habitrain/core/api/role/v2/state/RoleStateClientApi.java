package com.habitrain.core.api.role.v2.state;

import com.habitrain.core.api.spi.RoleSpi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Read-only view of state actually synchronized to this physical client.
 * Call on the client thread after client initialization. Never reads server
 * memory, even in singleplayer, and never exposes internal network payloads.
 */
@Environment(EnvType.CLIENT)
public interface RoleStateClientApi {
    static RoleStateClientApi instance() {
        if (RoleSpi.stateClientOrNull() instanceof RoleStateClientApi api) return api;
        throw new IllegalStateException("RoleStateClientApi is unavailable before client initialization");
    }

    /**
     * Reads an exact id/role/scope/world/owner slot using its registered codec.
     * PLAYER requires owner (null throws IllegalArgumentException) and ignores
     * world; WORLD/ROUND ignore owner.
     * Missing, removed and disconnected slots return MISSING, without inventing
     * a server value. A present null has status PRESENT and value null.
     * Unknown schemas, version/scope/type mismatches and invalid bytes are
     * unavailable results. Each read decodes a fresh value; callers cannot
     * mutate the mirror. Only committed full snapshots are visible.
     */
    <T> RoleStateClientValue<T> read(RoleStateKey<T> key, StateScope scope,
                                    @Nullable ResourceLocation worldKey, @Nullable UUID owner);
}
