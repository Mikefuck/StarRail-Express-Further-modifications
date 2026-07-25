package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

@FunctionalInterface
public interface NamePatch {
    Component getName(SRERole original, MinecraftServer server);
}
