package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;

/** Transforms one resolved role text while preserving access to its baseline. */
@FunctionalInterface
public interface RoleTextPatch {
    Component apply(SRERole original, Component baseline);
}
