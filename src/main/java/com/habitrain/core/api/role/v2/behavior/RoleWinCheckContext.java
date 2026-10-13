package com.habitrain.core.api.role.v2.behavior;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerLevel;

/** Context supplied to a v2 declarative win-condition hook. */
public record RoleWinCheckContext(
        ServerLevel level,
        SRERole targetRole,
        boolean roleIsModified,
        boolean roleIsReplaced
) {}
