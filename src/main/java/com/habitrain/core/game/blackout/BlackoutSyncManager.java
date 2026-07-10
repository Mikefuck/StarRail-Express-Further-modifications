package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.network.BlackoutSheriffVotePayload;
import com.habitrain.core.network.BlackoutTimerPayload;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

class BlackoutSyncManager {
    private BlackoutTimerSnapshot lastTimerSnapshot;
    private int calibrationCounter = 0;

    private record BlackoutTimerSnapshot(int totalTimeRemaining, long endTimeTick,
                                          boolean isPermanent, int phaseOrdinal) {}

    void tickSecond(ServerLevel level) {
        var phase = BlackoutTimerSystem.getPhase(level);
        // Use world gameTime (same clock client uses via level.getGameTime()), NOT server process tickCount.
        long serverTick = level.getGameTime();
        long endTimeTick = phase == BlackoutTimerSystem.Phase.NORMAL
                ? serverTick + (long) BlackoutTimerSystem.getBlackoutCountdown(level) * 20L
                : (phase == BlackoutTimerSystem.Phase.MAINTENANCE
                    ? serverTick + (long) BlackoutTimerSystem.getMaintenanceTime(level) * 20L
                    : 0L);

        BlackoutTimerSnapshot current = new BlackoutTimerSnapshot(
                BlackoutTimerSystem.getTotalTimeRemaining(level),
                endTimeTick,
                BlackoutTimerSystem.isPermanentBlackoutActive(level),
                phase.ordinal());

        calibrationCounter++;
        boolean forceCalibration = (calibrationCounter % 10 == 0);

        boolean shouldBroadcast = lastTimerSnapshot == null
                || current.totalTimeRemaining != lastTimerSnapshot.totalTimeRemaining
                || Math.abs(current.endTimeTick - lastTimerSnapshot.endTimeTick) > 2
                || current.isPermanent != lastTimerSnapshot.isPermanent
                || current.phaseOrdinal != lastTimerSnapshot.phaseOrdinal
                || forceCalibration;

        if (shouldBroadcast) {
            BlackoutTimerPayload.broadcastToAll(level.getServer(),
                    current.totalTimeRemaining, current.endTimeTick,
                    current.isPermanent, current.phaseOrdinal);
            lastTimerSnapshot = current;
        }
    }

    void syncReset(ServerLevel level) {
        if (level == null || level.getServer() == null) return;
        BlackoutTimerPayload.broadcastToAll(level.getServer(), 0, 0L, false, 0);
        BlackoutSheriffVotePayload.broadcastToAll(level.getServer(), false, 0, 15, 1, List.of());
        lastTimerSnapshot = null;
        calibrationCounter = 0;
    }

    void broadcast(ServerLevel level, String message) {
        if (level == null) return;
        Component component = Component.literal(message);
        for (ServerPlayer player : level.players()) {
            SubtitleNotifier.sendTop(player, Component.empty(), component, 80);
        }
    }

    void onPreStart() {
        lastTimerSnapshot = null;
        calibrationCounter = 0;
    }
}
