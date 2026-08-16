package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface SpawnInfoPatch {
    void apply(SRERole original, @Nullable MinecraftServer server, MutableSpawnInfoPatch out);

    public static final class MutableSpawnInfoPatch {
        public Integer defaultMax;
        public Integer defaultEnableChance;
        public Integer defaultEnableNeededPlayerCount;
        public Integer defaultEnableMaxPlayerCount;
    }
}
