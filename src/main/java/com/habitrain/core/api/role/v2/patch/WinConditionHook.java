package com.habitrain.core.api.role.v2.patch;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.v2.behavior.RoleWinCheckContext;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface WinConditionHook {
    @Nullable WinResult check(RoleWinCheckContext ctx);
}
