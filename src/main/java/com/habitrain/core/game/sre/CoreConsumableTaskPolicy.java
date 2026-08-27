package com.habitrain.core.game.sre;

import java.util.Set;

/** Global ownership policy for the two consumable tasks replaced by Core. */
public final class CoreConsumableTaskPolicy {

    private static final Set<String> REPLACED_UPSTREAM_TASKS = Set.of("EAT", "DRINK");

    private CoreConsumableTaskPolicy() {
    }

    public static boolean replacesUpstream(String upstreamTaskName) {
        return upstreamTaskName != null && REPLACED_UPSTREAM_TASKS.contains(upstreamTaskName);
    }
}
