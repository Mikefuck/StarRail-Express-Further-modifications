package com.habitrain.core.api.role.v2;

/**
 * Public access point for the v2 Role Extension platform ({@code ADD},
 * {@code MODIFY}, {@code REPLACE}, {@code ALIAS}).
 *
 * <p>Providers register through the {@code habitrain:role_extensions} entrypoint
 * ({@link RoleExtensionEntrypoint}), where each provider is handed a
 * provider-scoped {@link RoleExtensionRegistrar} inside its own transaction.
 * {@link #loadProviders()} drives that entrypoint (called by core during
 * initialization); {@link #apiVersion()} lets providers/manifests check the
 * supported API level during registration.
 *
 * <p><b>{@link #registrar()} is read-only (audit P1-1, review 2026-08-14):</b>
 * it is retained only for compatibility and introspection; every method on the
 * returned registrar throws. All declarations — ADD/MODIFY/REPLACE/ALIAS,
 * hooks, state, action, voice, chat — must go through the provider-scoped
 * registrar obtained from {@code habitrain:role_extensions}.
 */
public interface RoleExtensionApi {

    /** The process-wide platform instance. */
    static RoleExtensionApi instance() {
        return DefaultHolder.INSTANCE;
    }

    /** Lazily-bound default instance; avoids touching the role registry on class load. */
    final class DefaultHolder {
        private DefaultHolder() {}

        static final RoleExtensionApi INSTANCE =
                new com.habitrain.core.role.extension.RoleExtensionServiceImpl();
    }

    /**
     * Read-only registrar facade retained for compatibility (audit P1-1,
     * review 2026-08-14). Every registration method on it throws: declarations
     * are only possible through the provider-scoped registrar handed to
     * {@code habitrain:role_extensions} entrypoints. Use it only for
     * introspection, never for registration.
     */
    RoleExtensionRegistrar registrar();

    /**
     * The supported v2 Role Extension API version. Stable within an increment;
     * major/minor bumps signal contract changes to providers.
     */
    String apiVersion();

    /**
     * Whether an optional capability adapter is bound this process
     * (design §18.4). Missing voicechat stays {@code false}.
     */
    default boolean supports(com.habitrain.core.api.role.v2.capability.RoleCapabilityKey key) {
        return com.habitrain.core.api.role.v2.capability.RoleCapabilityApi.instance().supports(key);
    }

    /**
     * Drives every {@code habitrain:role_extensions} entrypoint, giving each
     * provider its {@link RoleExtensionRegistrar}. Idempotent; called by core
     * during initialization before the registry is frozen.
     */
    void loadProviders();
}