package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.server.MinecraftServer;

import java.util.List;

@FunctionalInterface
public interface ShopPatch {
    List<ShopEntry> getShopEntries(SRERole original, MinecraftServer server);
}
