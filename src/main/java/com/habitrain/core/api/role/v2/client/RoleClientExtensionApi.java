package com.habitrain.core.api.role.v2.client;

/**
 * Public client-extension service. The registry itself is common so tests
 * and diagnostics can read HUD / instinct declarations; entrypoint loading
 * is client-only.
 */
public interface RoleClientExtensionApi extends RoleClientExtensionRegistrar {

    static RoleClientExtensionApi instance() {
        return DefaultHolder.INSTANCE;
    }

    final class DefaultHolder {
        private DefaultHolder() {}

        static final RoleClientExtensionApi INSTANCE =
                new com.habitrain.core.role.client.RoleClientExtensionRegistry();
    }

    /**
     * Loads {@code habitrain:role_client_extensions} entrypoints. Must only
     * be called from the physical client.
     */
    void loadProviders();

    void freeze();

    boolean isFrozen();
}
