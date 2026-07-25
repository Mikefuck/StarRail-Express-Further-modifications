package com.habitrain.core.api.role.patch;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.BlackoutWinCheckContext;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface WinConditionHook {
    @Nullable WinResult check(BlackoutWinCheckContext ctx);
}
