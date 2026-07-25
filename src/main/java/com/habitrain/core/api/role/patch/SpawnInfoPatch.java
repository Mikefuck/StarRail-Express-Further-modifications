package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;

@FunctionalInterface
public interface SpawnInfoPatch {
    void apply(SRERole original, MinecraftServer server, MutableSpawnInfoPatch out);

    public static final class MutableSpawnInfoPatch {
        public Integer defaultMax;
        public Integer defaultEnableChance;
        public Integer defaultEnableNeededPlayerCount;
        public Integer defaultEnableMaxPlayerCount;
    }
}
