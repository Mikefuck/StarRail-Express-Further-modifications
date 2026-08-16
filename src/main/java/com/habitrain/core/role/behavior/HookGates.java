package com.habitrain.core.role.behavior;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime question the dispatcher asks before firing a broadcast hook: whether
 * the registered role currently has a holder, and whether it is part of this
 * round's effective pool. Both answers must come from the frozen round snapshot,
 * never from a pending snapshot or live registration.
 *
 * <p>The dispatcher holds one implementation; core binds a snapshot-backed one at
 * runtime and unit tests inject a strict one to verify gating. {@link
 * RoleScopeEvaluator#LENIENT} (always {@code true}) is the default so pure
 * dispatch tests keep their existing coverage.
 */
public interface HookGates {

    /** Whether at least one online player currently holds the role. */
    boolean activeHolder(RoleKey role, @Nullable ServerLevel level);

    /** Whether the role is present in the current round snapshot. */
    boolean presentInRound(RoleKey role, @Nullable ServerLevel level);
}
