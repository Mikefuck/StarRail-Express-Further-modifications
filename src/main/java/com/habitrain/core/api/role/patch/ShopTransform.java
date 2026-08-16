package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Transforms the fully resolved shop list, including role methods, custom
 * entries and SRE defaults.
 */
@FunctionalInterface
public interface ShopTransform {
    List<ShopEntry> transform(
            SRERole original,
            @Nullable MinecraftServer server,
            List<ShopEntry> resolvedEntries);
}
