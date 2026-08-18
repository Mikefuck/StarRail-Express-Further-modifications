package com.habitrain.core.api.role.v2.client;

/**
 * Process-wide, read-only client-extension service. Providers must register
 * through the {@code habitrain:role_client_extensions} entrypoint, which
 * receives a provider-scoped {@link RoleClientExtensionRegistrar}. This facade
 * exposes only read operations; deprecated write-shaped compatibility methods
 * always throw so provider identity, namespace validation, handshake
 * declarations and config gating cannot be bypassed.
 */
public interface RoleClientExtensionApi {

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

    /**
     * The provider mod ids whose client-extension registration committed
     * (audit P1-4). Reported in the handshake so the server can fail closed
     * when a required provider's client extensions are missing or failed to
     * load.
     */
    default java.util.Set<String> loadedProviderIds() {
        return java.util.Set.of();
    }

    /** @deprecated Register through the provider-scoped entrypoint registrar. */
    @Deprecated
    default void hud(RoleHudSpec spec) { throw directRegistrationUnsupported(); }

    /** @deprecated Register through the provider-scoped entrypoint registrar. */
    @Deprecated
    default void instinct(RoleInstinctRule rule) { throw directRegistrationUnsupported(); }

    /** @deprecated Register through the provider-scoped entrypoint registrar. */
    @Deprecated
    default void skin(RoleSkinSpec spec) { throw directRegistrationUnsupported(); }

    /** @deprecated Register through the provider-scoped entrypoint registrar. */
    @Deprecated
    default void nameRender(RoleNameRenderRule rule) { throw directRegistrationUnsupported(); }

    /** @deprecated Register through the provider-scoped entrypoint registrar. */
    @Deprecated
    default void hudWidget(net.minecraft.resources.ResourceLocation id, String entryKey,
                           com.habitrain.core.api.role.v2.RoleKey role, RoleHudWidget widget) {
        throw directRegistrationUnsupported();
    }

    /** @deprecated Register through the provider-scoped entrypoint registrar. */
    @Deprecated
    default void hudWidget(com.habitrain.core.api.role.v2.RoleKey role, RoleHudWidget widget) {
        throw directRegistrationUnsupported();
    }

    /** @deprecated Register through the provider-scoped entrypoint registrar. */
    @Deprecated
    default void screen(RoleScreenSpec spec) { throw directRegistrationUnsupported(); }

    private static UnsupportedOperationException directRegistrationUnsupported() {
        return new UnsupportedOperationException(
                "Direct client-extension registration is not supported; register through "
                        + "the habitrain:role_client_extensions entrypoint and its provider-scoped registrar");
    }

    java.util.Collection<RoleHudSpec> huds();

    java.util.List<RoleHudSpec> hudsFor(com.habitrain.core.api.role.v2.RoleKey role);

    java.util.Collection<RoleInstinctRule> instincts();

    java.util.List<RoleInstinctRule> instinctsFor(com.habitrain.core.api.role.v2.RoleKey viewerRole);

    java.util.Collection<RoleSkinSpec> skins();

    java.util.List<RoleSkinSpec> skinsFor(com.habitrain.core.api.role.v2.RoleKey role);

    java.util.Collection<RoleNameRenderRule> nameRenders();

    java.util.List<RoleNameRenderRule> nameRendersFor(com.habitrain.core.api.role.v2.RoleKey role);

    java.util.Collection<RoleHudWidget> hudWidgetsFor(com.habitrain.core.api.role.v2.RoleKey role);

    java.util.Collection<RoleScreenSpec> screens();

    java.util.List<RoleScreenSpec> screensFor(com.habitrain.core.api.role.v2.RoleKey role);

    void freeze();

    boolean isFrozen();
}
