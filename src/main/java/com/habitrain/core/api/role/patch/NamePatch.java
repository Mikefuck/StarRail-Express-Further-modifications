package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface NamePatch {
    Component getName(SRERole original, @Nullable MinecraftServer server);
}
