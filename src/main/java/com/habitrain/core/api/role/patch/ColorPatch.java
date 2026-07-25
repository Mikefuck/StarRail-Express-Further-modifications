package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;

@FunctionalInterface
public interface ColorPatch {
    int getColor(SRERole original, MinecraftServer server);
}
