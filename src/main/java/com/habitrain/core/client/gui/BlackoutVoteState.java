package com.habitrain.core.client.gui;

import com.habitrain.core.network.BlackoutVotePayload;
import com.habitrain.core.network.VotePurpose;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BlackoutVoteState {
    private static VotePurpose purpose = VotePurpose.EXILE;
    private static boolean active = false;
    private static int remainingSeconds = 0;
    private static int totalSeconds = 15;
    private static String title = "";
    private static String description = "";
    private static List<BlackoutVotePayload.Entry> candidates = List.of();
    private static UUID selectedTargetId = null;

    private BlackoutVoteState() {}

    public static void update(BlackoutVotePayload payload) {
        purpose = payload.purpose();
        active = payload.active();
        remainingSeconds = payload.remainingSeconds();
        totalSeconds = payload.totalSeconds();
        title = payload.title();
        description = payload.description();
        candidates = List.copyOf(payload.candidates());
        // 投票结束（!active）或新投票开始（active 且 remainingSeconds==totalSeconds）时清空上次选择，
        // 避免上一轮选中状态残留到下一轮（M2-L1）。
        boolean freshVote = active && remainingSeconds == totalSeconds;
        if (!active || freshVote) {
            selectedTargetId = null;
        }
    }

    public static void clear() {
        active = false;
        remainingSeconds = 0;
        candidates = List.of();
        selectedTargetId = null;
    }

    public static boolean isActive() { return active; }
    public static int getRemainingSeconds() { return remainingSeconds; }
    /** 设置剩余秒数（供客户端 tick 本地递减） */
    public static void setRemainingSeconds(int seconds) { remainingSeconds = seconds; }
    public static int getTotalSeconds() { return totalSeconds; }
    public static String getTitle() { return title; }
    public static String getDescription() { return description; }
    public static VotePurpose getPurpose() { return purpose; }
    public static List<BlackoutVotePayload.Entry> getCandidates() { return candidates; }
    public static boolean isSelected(UUID id) { return id.equals(selectedTargetId); }

    public static void toggleSelection(UUID targetId) {
        if (selectedTargetId != null && selectedTargetId.equals(targetId)) {
            selectedTargetId = null;
        } else {
            selectedTargetId = targetId;
        }
    }
}
