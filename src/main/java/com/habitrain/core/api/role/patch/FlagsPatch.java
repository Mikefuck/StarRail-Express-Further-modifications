package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/** Consumer that mutates a mutable flags patch description. */
@FunctionalInterface
public interface FlagsPatch {
    void apply(SRERole original, @Nullable MinecraftServer server, MutableFlagsPatch out);

    public static final class MutableFlagsPatch {
        public Boolean isInnocent;
        public Boolean canUseKiller;
        public Boolean isNeutrals;
        public Boolean isVigilanteTeam;
        public Boolean isNeutralForKiller;
        public Boolean isNeutralForInnocent;
        public Boolean canUseInstinct;
        public Boolean instinctNightVision;
        public Boolean canSeeTeammateKiller;
    }
}
