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

    public record UpdateResult(boolean shouldAutoOpen,
                               boolean shouldClose,
                               boolean shouldStartMapTransition,
                               String resolvedOptionId) {}

    /**
     * Apply S2C payload. Returns open/close hints so the network receiver can
     * auto-open once per phase without reopening on 1Hz rebroadcasts.
     */
    public static UpdateResult update(OptionVotePayload payload) {
        boolean wasActive = active;
        String oldVoteId = voteId;

        String newVoteId = payload.voteId() == null ? "" : payload.voteId();
        boolean voteIdChanged = !newVoteId.equals(oldVoteId);
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

        boolean shouldAutoOpen = active && (!wasActive || voteIdChanged);
        boolean shouldClose = !active;
        String resolvedOptionId = payload.resolvedOptionId() == null
                ? "" : payload.resolvedOptionId();
        boolean shouldStartMapTransition = !active
                && "map".equals(voteId)
                && !resolvedOptionId.isBlank();
        return new UpdateResult(shouldAutoOpen, shouldClose,
                shouldStartMapTransition, resolvedOptionId);
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

    public static String getTitle() {
        return title;
    }

    public static String getDescription() {
        return description;
    }

    public static List<OptionVotePayload.Entry> getCandidates() {
        return candidates;
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
