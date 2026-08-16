package com.habitrain.core.api.role.v2;

/**
 * Entrypoint implemented by mods that want to extend the role platform through
 * the v2 model ({@code ADD}/{@code MODIFY}/{@code REPLACE}/{@code ALIAS}).
 *
 * <p>Registered under the {@code habitrain:role_extensions} entrypoint key in
 * {@code fabric.mod.json}. Core invokes {@link #register} during mod
 * initialization (before the role registry is frozen), giving the provider a
 * chance to declare new roles, patch existing ones, replace them or add aliases.
 *
 * <pre>{@code
 * {
 *   "entrypoints": {
 *     "habitrain:role_extensions": ["com.example.roles.ExampleRoleProvider"]
 *   }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface RoleExtensionEntrypoint {

    /**
     * Called once during initialization. Providers call {@code registrar.add(...)},
     * {@code registrar.modify(...)}, {@code registrar.replace(...)} or
     * {@code registrar.alias(...)} to declare their intent; definitions are
     * validated and frozen by core.
     */
    void register(RoleExtensionRegistrar registrar);

    /**
     * Whether this provider ships client-side role content (HUD / instinct /
     * skins / screens) and therefore must be present AND loaded on the client
     * (audit P1-4). An explicit declaration replaces the old heuristic of
     * guessing from entrypoint presence, so the handshake can fail closed for
     * clients that lack the provider's client extensions.
     *
     * <p>Default {@code false}: server-only providers do not constrain the
     * client.
     */
    default boolean requiresClient() {
        return false;
    }
}
