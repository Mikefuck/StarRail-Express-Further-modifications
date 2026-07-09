package com.habitrain.core.client.gui;

import com.habitrain.core.network.BlackoutSheriffVotePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class BlackoutSheriffVoteState {
    private static boolean active = false;
    private static int remainingSeconds = 0;
    private static int totalSeconds = 15;
    private static int sheriffCount = 1;
    private static List<BlackoutSheriffVotePayload.Entry> candidates = List.of();
    private static List<UUID> selectedTargetIds = new ArrayList<>();

    private BlackoutSheriffVoteState() {}

    public static void update(BlackoutSheriffVotePayload payload) {
        active = payload.active();
        remainingSeconds = payload.remainingSeconds();
        totalSeconds = payload.totalSeconds();
        sheriffCount = payload.sheriffCount();
        candidates = List.copyOf(payload.players());
        if (!active) {
            selectedTargetIds.clear();
            return;
        }
        Set<UUID> candidateIds = candidates.stream().map(BlackoutSheriffVotePayload.Entry::playerId).collect(Collectors.toSet());
        selectedTargetIds.removeIf(id -> !candidateIds.contains(id));
    }

    public static void clear() {
        active = false;
        remainingSeconds = 0;
        totalSeconds = 15;
        sheriffCount = 1;
        candidates = List.of();
        selectedTargetIds.clear();
    }

    public static boolean isActive() {
        return active;
    }

    public static int getRemainingSeconds() {
        return remainingSeconds;
    }

    public static int getSheriffCount() {
        return sheriffCount;
    }

    public static List<BlackoutSheriffVotePayload.Entry> getCandidates() {
        return candidates;
    }

    public static List<UUID> getSelectedTargetIds() {
        return selectedTargetIds;
    }

    public static boolean isSelected(UUID id) {
        return selectedTargetIds.contains(id);
    }

    public static void toggleSelection(UUID targetId) {
        int idx = selectedTargetIds.indexOf(targetId);
        if (idx >= 0) {
            selectedTargetIds.remove(idx);
        } else if (selectedTargetIds.size() < sheriffCount) {
            selectedTargetIds.add(targetId);
        } else {
            // 已选满，替换第一个
            selectedTargetIds.set(0, targetId);
        }
    }

}