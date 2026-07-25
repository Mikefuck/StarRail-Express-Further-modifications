package com.habitrain.core.api.role;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerLevel;

public record BlackoutWinCheckContext(
        ServerLevel level,
        SRERole targetRole,
        boolean roleIsModified,
        boolean roleIsReplaced
) {}
