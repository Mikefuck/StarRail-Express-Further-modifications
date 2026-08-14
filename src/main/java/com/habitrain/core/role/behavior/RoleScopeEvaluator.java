package com.habitrain.core.role.behavior;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.behavior.RoleScope;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Maps a registered {@link RoleScope} to a concrete gate for broadcast events.
 *
 * <p>Per-player dispatches already resolved the relevant player's current role,
 * so they never consult this evaluator. Broadcasts (any-death, any-buy, meeting,
 * game start/end, tick, win) iterate every registered role and skip entries whose
 * scope does not hold for the current round.
 *
 * <p>Semantics (fix-doc §9.2): {@code HOLDER}/{@code KILLER}/{@code VICTIM}/
 * {@code TARGET}/{@code ANY_ACTIVE_HOLDER} require an active holder;
 * {@code ROUND_PRESENT} requires the role in the round snapshot;
 * {@code GLOBAL_WHILE_ENABLED} requires both a present role and the dedicated
 * global-hook gate. The latter keeps globally scoped behavior under the same
 * server-authoritative configuration as every other v2 declaration.
 */
public final class RoleScopeEvaluator {

    /** Lenient default: every registered hook fires. Used by tests and pre-snapshot. */
    public static final HookGates LENIENT = new HookGates() {
        @Override
        public boolean activeHolder(RoleKey role, @Nullable ServerLevel level) {
            return true;
        }

        @Override
        public boolean presentInRound(RoleKey role, @Nullable ServerLevel level) {
            return true;
        }
    };

    private RoleScopeEvaluator() {}

    /** Whether an entry with the given scope should fire. {@code null} gates → lenient. */
    public static boolean evaluate(RoleScope scope, RoleKey role, @Nullable ServerLevel level,
                                   @Nullable HookGates gates) {
        HookGates g = gates == null ? LENIENT : gates;
        return switch (scope) {
            case HOLDER, KILLER, VICTIM, TARGET, ANY_ACTIVE_HOLDER -> g.activeHolder(role, level);
            case ROUND_PRESENT -> g.presentInRound(role, level);
            case GLOBAL_WHILE_ENABLED -> g.presentInRound(role, level)
                    && com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE.isAllowGlobalHooks();
        };
    }

    /** Snapshot-aware variant used by the live dispatcher. */
    public static boolean evaluate(RoleScope scope, RoleKey role, @Nullable ServerLevel level,
                                   @Nullable HookGates gates,
                                   @Nullable com.habitrain.core.api.role.v2.RoleSnapshot snapshot) {
        if (scope == RoleScope.GLOBAL_WHILE_ENABLED && snapshot != null) {
            return gates != null && gates.presentInRound(role, level) && snapshot.allowGlobalHooks();
        }
        return evaluate(scope, role, level, gates);
    }
}
