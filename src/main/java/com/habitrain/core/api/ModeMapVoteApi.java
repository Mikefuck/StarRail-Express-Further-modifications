package com.habitrain.core.api;

import com.habitrain.core.vote.ModeMapVoteOrchestrator;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Public API for the two-phase mode→map lobby vote.
 */
public final class ModeMapVoteApi {
    private ModeMapVoteApi() {}

    public static boolean start(ServerLevel level) {
        return start(level, new ModeMapVoteConfig());
    }

    public static boolean start(ServerLevel level, ModeMapVoteConfig config) {
        return ModeMapVoteOrchestrator.start(level, config != null ? config : new ModeMapVoteConfig());
    }

    public static boolean cancel(ServerLevel level) {
        return ModeMapVoteOrchestrator.cancel(level);
    }

    public static boolean isRunning(ServerLevel level) {
        return ModeMapVoteOrchestrator.isRunning(level);
    }

    public static Optional<ModeMapVoteSnapshot> getSnapshot(ServerLevel level) {
        return Optional.ofNullable(ModeMapVoteOrchestrator.snapshot(level));
    }
}
