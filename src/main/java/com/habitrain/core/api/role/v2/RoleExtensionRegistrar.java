package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.RoleAlias;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.action.RoleActionSpec;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleScope;
import com.habitrain.core.api.role.v2.capability.RoleChatPolicy;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.state.RoleStateKey;
import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import io.wifi.starrailexpress.api.SRERole;

/**
 * Registration facade offered to {@link RoleExtensionEntrypoint} providers
 * during the registration phase.
 *
 * <p>Exposes the four v2 registration operations: {@code ADD} (declare a brand-new
 * role), {@code MODIFY} (reversibly patch an existing role), {@code REPLACE}
 * (hide a target and surface a replacement) and {@code ALIAS} (redirect an old
 * id to a canonical id). The provider's mod id is captured automatically by core
 * from the entrypoint, so definitions do not need to declare it.
 */
public interface RoleExtensionRegistrar {

    /**
     * Adds a brand-new role from a declarative {@link RoleDefinition}.
     *
     * <p>The definition's id must live in the provider's own namespace and must
     * not collide with an already-registered role. Core compiles it into an
     * upstream role, registers it once into the role registry, and returns the
     * compiled instance (so the provider can, e.g., attach skills by reference).
     *
     * @return the compiled, registered role instance
     * @throws IllegalArgumentException on namespace/duplicate/ownership violations
     */
    SRERole add(RoleDefinition definition);

    /**
     * Registers a reversible {@code MODIFY} patch on an existing role.
     *
     * <p>The target keeps its id and original object; the patch layers field
     * operations on top and is fully undone when disabled. Multiple providers may
     * patch the same target, ordered by {@link com.habitrain.core.api.role.v2.definition.PatchPriority}
     * then provider mod id then {@code entryKey}.
     *
     * @throws IllegalArgumentException on validation violations
     */
    void modify(RolePatch patch);

    /**
     * Registers a {@code REPLACE} operation that hides a target role and surfaces
     * a replacement. Only one replacement may own a given target.
     *
     * @throws IllegalArgumentException on validation violations
     */
    void replace(RoleReplacement replacement);

    /**
     * Registers an {@code ALIAS} redirecting an old / legacy id to a canonical id.
     *
     * @throws IllegalArgumentException on validation violations
     */
    void alias(RoleAlias alias);

    /**
     * Attaches managed behavior hooks to a role. Core registers one global
     * listener per event and dispatches to the hooks of the relevant role, so
     * providers never add permanent listeners to the global event bus.
     *
     * @throws IllegalArgumentException on validation violations
     */
    void hooks(RoleKey role, RoleHooks hooks);

    /**
     * Attaches managed behavior hooks to a role with an explicit {@link RoleScope}.
     * The scope gates broadcast events (any-death, any-buy, meeting, game start/end,
     * tick, win): {@code HOLDER}/{@code ANY_ACTIVE_HOLDER} only fire when someone
     * currently holds the role, {@code ROUND_PRESENT} when the role is in the
     * current round snapshot, and {@code GLOBAL_WHILE_ENABLED} whenever the entry is
     * enabled. Defaults to {@link RoleScope#HOLDER}.
     *
     * @throws IllegalArgumentException on validation violations
     */
    default void hooks(RoleKey role, RoleScope scope, RoleHooks hooks) {
        hooks(role, hooks);
    }

    /**
     * Registers a namespaced role-state schema. Providers declare the type,
     * scope, default, persistence and reset causes; core owns storage and
     * reset. Must be called during the registration phase.
     *
     * @return the typed handle used for later get/set
     * @throws IllegalArgumentException on duplicate id+role or missing fields
     */
    <T> RoleStateKey<T> state(RoleStateSpec<T> spec);

    /**
     * Registers a namespaced role action. Core multiplexes one C2S/S2C packet
     * pair and enforces size, rate, cooldown and current-role gates. Must be
     * called during the registration phase.
     *
     * @return the registered spec
     * @throws IllegalArgumentException on duplicate id or missing fields
     */
    RoleActionSpec action(RoleActionSpec spec);

    /**
     * Registers a voice-chat policy. Core stores the semantic; an optional
     * voicechat adapter applies it when that mod is present.
     */
    RoleVoicePolicy voice(RoleVoicePolicy policy);

    /** Registers a chat mute policy. */
    RoleChatPolicy chat(RoleChatPolicy policy);
}
