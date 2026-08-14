package com.habitrain.core.api.role.v2;

/**
 * Public access point for the v2 Role Extension platform ({@code ADD},
 * {@code MODIFY}, {@code REPLACE}, {@code ALIAS}).
 *
 * <p>Providers use {@link RoleExtensionEntrypoint}; consumers and tooling use
 * {@link #registrar()} (rarely) and {@link #loadProviders()} (driven by core
 * during initialization). {@link #apiVersion()} lets providers/manifests check
 * the supported API level during registration.
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

    /** The registrar through which new roles are declared during registration. */
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
