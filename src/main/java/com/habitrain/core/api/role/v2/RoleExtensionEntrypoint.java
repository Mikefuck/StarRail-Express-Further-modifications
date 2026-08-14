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
}
