package com.habitrain.core.client.gui;

import net.minecraft.util.Mth;

/**
 * 开局加载/转场的客户端会话状态。
 *
 * <p>进度与路径标志不绑在 {@link VoteLaunchTransitionScreen} 上，因此玩家在加载期隐藏 UI 后，
 * 进度包仍可写入；判定点 A（地图重置完成）与判定点 B（环境就绪）据此在「可见路径 / 补盖路径」
 * 之间分流。</p>
 *
 * <p>{@code hiddenByUser} 是 <b>sticky 隐藏意图</b>（本局是否选过隐藏加载），不是「当前 UI 是否关闭」。
 * V 键重开加载页只改变可见性，不得清除此标志；判定点 A 才把它消费为 {@code recoverPath}。</p>
 */
public final class VoteLaunchSession {
    private static boolean active;
    /**
     * Sticky 隐藏意图：玩家在加载期至少选过一次隐藏。
     * 与「当前是否打开 VoteLaunchTransitionScreen」解耦；V 偷看不得 clear。
     */
    private static boolean hiddenByUser;
    private static boolean startConfirmed;
    private static boolean launchConfirmed;
    private static boolean gameActive;
    private static boolean recoverPath;
    private static boolean hideLocked;
    /** hide 后再开加载页时跳过完整右→左滑入。 */
    private static boolean enterCompletedOnce;

    private static int progress;
    private static int playerCount;
    private static int killerCount;
    private static String mapId = "";
    private static String modeId = "";
    private static String winningMapId = "";

    private VoteLaunchSession() {}

    /**
     * 判定点 A 结果：是否需要强制开屏，以及是否走左→右补盖路径。
     */
    public record StartConfirmedResult(boolean forceOpen, boolean recover) {}

    /** 地图投票结束、打开加载转场时调用。 */
    public static void begin(String resolvedMapId) {
        // 已在本局加载会话中：只同步 mapId，禁止 clear 冲掉 hide/recover 意图
        if (active) {
            applyMapId(resolvedMapId);
            return;
        }
        clear();
        active = true;
        String id = resolvedMapId == null ? "" : resolvedMapId;
        winningMapId = id;
        mapId = id;
    }

    public static void updateProgress(int prog, int players, int killers, String map, String mode) {
        if (!active) return;
        progress = Mth.clamp(prog, 0, 100);
        if (players > 0) playerCount = players;
        if (killers >= 0) killerCount = killers;
        if (map != null && !map.isBlank()) {
            mapId = map;
            winningMapId = map;
        }
        if (mode != null && !mode.isBlank()) {
            modeId = mode;
        }
    }

    /**
     * 仅加载期、判定点 A 前可隐藏。
     */
    public static boolean canHide() {
        return active && !hideLocked && !startConfirmed && !launchConfirmed && !recoverPath;
    }

    /**
     * 已隐藏且仍在加载窗口：可用 V 键重开加载页（不清除 sticky 意图）。
     */
    public static boolean canReopenLoading() {
        return active && hiddenByUser && canHide();
    }

    public static void markHiddenByUser() {
        if (!canHide()) return;
        hiddenByUser = true;
        enterCompletedOnce = true;
    }

    /**
     * 仅用于整场会话复位。不要在 V 重开加载时调用——sticky 意图必须跨偷看保留，
     * 否则判定点 A 会误判为可见路径并继续显示「开局加载中」。
     */
    public static void clearHiddenByUser() {
        hiddenByUser = false;
    }

    /**
     * 判定点 A：地图重置完成（trueStartGame → startConfirmed）。
     *
     * <ul>
     *   <li>sticky 隐藏意图 → 补盖路径，强制开屏左→右盖住 TP</li>
     *   <li>未隐藏 → 锁定 hide，保持现有加载 UI；若屏意外丢失由接收器强制开屏</li>
     * </ul>
     */
    public static StartConfirmedResult onStartConfirmed(String confirmedMapId) {
        if (!active) {
            return new StartConfirmedResult(false, false);
        }
        applyMapId(confirmedMapId);
        if (startConfirmed) {
            // 幂等：仅 recover 路径需要强制补开；不再用 hiddenByUser（可能已消费）
            return new StartConfirmedResult(recoverPath, recoverPath);
        }
        startConfirmed = true;
        hideLocked = true;

        if (hiddenByUser) {
            // 消费 sticky 意图 → recover 路径
            hiddenByUser = false;
            recoverPath = true;
            // 补盖直接显示「对局开始」，不再走加载进度内容
            launchConfirmed = true;
            return new StartConfirmedResult(true, true);
        }
        // 可见路径：不强制改动画；接收器若发现屏不在转场上会保险开屏
        return new StartConfirmedResult(false, false);
    }

    /**
     * 判定点 B 前：若 A 漏处理但玩家曾 sticky 隐藏，迟到 promote 为 recover。
     *
     * @return true 若本次调用把路径提升为 recover
     */
    public static boolean promoteStickyHideToRecoverIfNeeded() {
        if (!active || recoverPath || !hiddenByUser) {
            return false;
        }
        hiddenByUser = false;
        recoverPath = true;
        startConfirmed = true;
        hideLocked = true;
        launchConfirmed = true;
        return true;
    }

    /**
     * 判定点 B：环境就绪。可见路径触发原地切标题；补盖路径仅同步 mapId。
     *
     * @return true 若本调用首次将 launchConfirmed 置真（可见路径需要 content-switch 计时）
     */
    public static boolean onLaunchConfirmed(String confirmedMapId) {
        if (!active) return false;
        applyMapId(confirmedMapId);
        hideLocked = true;
        if (launchConfirmed) {
            return false;
        }
        launchConfirmed = true;
        return true;
    }

    public static void onGameActive() {
        if (!active) return;
        gameActive = true;
        hideLocked = true;
    }

    public static void onAbort() {
        clear();
    }

    public static void clear() {
        active = false;
        hiddenByUser = false;
        startConfirmed = false;
        launchConfirmed = false;
        gameActive = false;
        recoverPath = false;
        hideLocked = false;
        enterCompletedOnce = false;
        progress = 0;
        playerCount = 0;
        killerCount = 0;
        mapId = "";
        modeId = "";
        winningMapId = "";
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isHiddenByUser() {
        return hiddenByUser;
    }

    public static boolean isStartConfirmed() {
        return startConfirmed;
    }

    public static boolean isLaunchConfirmed() {
        return launchConfirmed;
    }

    public static boolean isGameActive() {
        return gameActive;
    }

    public static boolean isRecoverPath() {
        return recoverPath;
    }

    public static boolean isHideLocked() {
        return hideLocked;
    }

    public static boolean isEnterCompletedOnce() {
        return enterCompletedOnce;
    }

    public static void markEnterCompletedOnce() {
        enterCompletedOnce = true;
    }

    public static int getProgress() {
        return progress;
    }

    public static int getPlayerCount() {
        return playerCount;
    }

    public static int getKillerCount() {
        return killerCount;
    }

    public static String getMapId() {
        return mapId;
    }

    public static String getModeId() {
        return modeId;
    }

    public static String getWinningMapId() {
        return winningMapId;
    }

    private static void applyMapId(String confirmedMapId) {
        if (confirmedMapId != null && !confirmedMapId.isBlank()) {
            winningMapId = confirmedMapId;
            mapId = confirmedMapId;
        }
    }
}
