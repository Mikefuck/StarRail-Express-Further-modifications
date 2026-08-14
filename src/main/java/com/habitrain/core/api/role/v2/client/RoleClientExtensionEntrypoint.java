package com.habitrain.core.api.role.v2.client;

/**
 * Client-only entrypoint ({@code habitrain:role_client_extensions}).
 *
 * <p>Dedicated servers never load this entrypoint. Providers that put
 * {@code MinecraftClient} in a common class will still crash a dedicated
 * server — keep those types in a client source set.
 */
public interface RoleClientExtensionEntrypoint {
    void register(RoleClientExtensionRegistrar registrar);
}
