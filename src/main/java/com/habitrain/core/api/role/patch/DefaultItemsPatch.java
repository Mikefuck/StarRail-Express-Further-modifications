package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

@FunctionalInterface
public interface DefaultItemsPatch {
    List<ItemStack> getDefaultItems(SRERole original, MinecraftServer server);
}
