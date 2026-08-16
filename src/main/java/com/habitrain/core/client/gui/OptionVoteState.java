package com.habitrain.core.client.gui;

import com.habitrain.core.network.MapVoteProfilePayload;
import com.habitrain.core.network.OptionVotePayload;

import java.util.List;
import java.util.Map;

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
    private static Map<String, MapVoteProfilePayload.MapProfile> profiles = Map.of();
    /**
     * 玩家在本轮大厅投票（模式→地图）中主动隐藏了投票 UI。
     * 隐藏后本轮所有投票页都不再自动弹出；开局转场页不受影响。
     */
    private static boolean uiHiddenByUser = false;
    /**
     * 地图投票结算后是否已触发过一次开局转场开屏。
     * 必须边沿触发：电平触发会在重处理同一 resolved 包时反复 begin()+重开加载页，
     * 清掉玩家的 hide 意图。
     */
    private static boolean mapLaunchTransitionConsumed = false;

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
        // voteId 切换时清空档案，避免 mode 阶段残留上一局 map 档案
        if (voteIdChanged) {
            profiles = Map.of();
        }

        String resolvedOptionId = payload.resolvedOptionId() == null
                ? "" : payload.resolvedOptionId();
        boolean mapResolved = !active
                && "map".equals(voteId)
                && !resolvedOptionId.isBlank();
        // 边沿触发：同一局地图结算只开一次加载转场，避免重处理清掉 hide 意图
        boolean shouldStartMapTransition = mapResolved && !mapLaunchTransitionConsumed;
        if (shouldStartMapTransition) {
            mapLaunchTransitionConsumed = true;
        }

        // 隐藏偏好跨 mode→map 保留；新一轮投票（非 mode→map 衔接）或地图投票结束时清除。
        if (mapResolved) {
            uiHiddenByUser = false;
        } else if (active && !wasActive) {
            // 新投票开始：允许下一轮地图结算再次开屏
            mapLaunchTransitionConsumed = false;
            boolean modeToMapHandoff = "map".equals(newVoteId) && "mode".equals(oldVoteId);
            if (!modeToMapHandoff) {
                uiHiddenByUser = false;
            }
        }

        // 玩家已隐藏时：本轮投票页不再自动弹出（转场由 shouldStartMapTransition 单独处理）
        boolean shouldAutoOpen = active
                && (!wasActive || voteIdChanged)
                && !uiHiddenByUser;
        boolean shouldClose = !active;
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
        profiles = Map.of();
        uiHiddenByUser = false;
        mapLaunchTransitionConsumed = false;
    }

    /** 玩家主动隐藏投票 UI（本轮 mode/map 均不再自动弹出）。 */
    public static void markUiHiddenByUser() {
        uiHiddenByUser = true;
    }

    /** 玩家手动重开投票 UI，恢复本轮自动弹出。 */
    public static void clearUiHiddenByUser() {
        uiHiddenByUser = false;
    }

    public static boolean isUiHiddenByUser() {
        return uiHiddenByUser;
    }

    /** 应用地图档案（S2C 一次性推送）。 */
    public static void applyProfiles(MapVoteProfilePayload payload) {
        if (payload == null || payload.profiles().isEmpty()) return;
        java.util.LinkedHashMap<String, MapVoteProfilePayload.MapProfile> merged =
                new java.util.LinkedHashMap<>(profiles);
        merged.putAll(payload.profiles());
        profiles = Map.copyOf(merged);
    }

    /** 取某地图的档案；无则返回 null。 */
    public static MapVoteProfilePayload.MapProfile getProfile(String mapId) {
        return mapId == null ? null : profiles.get(mapId);
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
