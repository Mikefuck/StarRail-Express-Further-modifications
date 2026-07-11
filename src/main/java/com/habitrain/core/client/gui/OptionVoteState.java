package com.habitrain.core.client.gui;

import com.habitrain.core.network.OptionVotePayload;

import java.util.List;

/**
 * 客户端通用选项投票状态（模式/地图等）。
 */
public final class OptionVoteState {
    private static String voteId = "";
    private static boolean active = false;
    private static int remainingSeconds = 0;
    private static int totalSeconds = 15;
    private static int maxSelections = 1;
    private static String title = "";
    private static String description = "";
    private static List<OptionVotePayload.Entry> candidates = List.of();
    private static String selectedOptionId = null;

    private OptionVoteState() {}

    public static void update(OptionVotePayload payload) {
        String newVoteId = payload.voteId() == null ? "" : payload.voteId();
        boolean voteIdChanged = !newVoteId.equals(voteId);
        voteId = newVoteId;
        active = payload.active();
        remainingSeconds = payload.remainingSeconds();
        totalSeconds = payload.totalSeconds();
        maxSelections = payload.maxSelections();
        title = payload.title() == null ? "" : payload.title();
        description = payload.description() == null ? "" : payload.description();
        candidates = List.copyOf(payload.candidates());
        // 投票结束或 voteId 切换时清空本地选择（勿用 remaining==total 判断新投票）
        if (!active || voteIdChanged) {
            selectedOptionId = null;
        }
    }

    public static void clear() {
        voteId = "";
        active = false;
        remainingSeconds = 0;
        candidates = List.of();
        selectedOptionId = null;
        title = "";
        description = "";
    }

    public static boolean isActive() {
        return active;
    }

    public static String getVoteId() {
        return voteId;
    }

    public static int getRemainingSeconds() {
        return remainingSeconds;
    }

    public static void setRemainingSeconds(int seconds) {
        remainingSeconds = seconds;
    }

    public static int getTotalSeconds() {
        return totalSeconds;
    }

    public static int getMaxSelections() {
        return maxSelections;
    }

    public static String getTitle() {
        return title;
    }

    public static String getDescription() {
        return description;
    }

    public static List<OptionVotePayload.Entry> getCandidates() {
        return candidates;
    }

    public static String getSelectedOptionId() {
        return selectedOptionId;
    }

    public static boolean isSelected(String optionId) {
        return optionId != null && optionId.equals(selectedOptionId);
    }

    public static void toggleSelection(String optionId) {
        if (selectedOptionId != null && selectedOptionId.equals(optionId)) {
            selectedOptionId = null;
        } else {
            selectedOptionId = optionId;
        }
    }
}
