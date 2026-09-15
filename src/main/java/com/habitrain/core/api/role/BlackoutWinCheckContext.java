package com.habitrain.core.api.role;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerLevel;

/** Legacy v1 hook context name retained for binary compatibility; also used by SRE wins. */
public record BlackoutWinCheckContext(
        ServerLevel level,
        SRERole targetRole,
        boolean roleIsModified,
        boolean roleIsReplaced
) {}
