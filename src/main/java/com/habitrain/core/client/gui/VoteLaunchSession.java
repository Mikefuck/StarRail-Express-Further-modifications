package com.habitrain.core.client.gui;

import net.minecraft.util.Mth;

/**
 * 开局加载/转场的客户端会话状态。
 *
 * <p>地图投票结算时 {@link #begin}，之后服务端进度包与判定点 A/B 依次写入；重置地图期间由
 * {@link MapResetProgressHud} 读取进度显示顶部进度牌，判定点 A 起由
 * {@link VoteLaunchTransitionScreen} 读取。进度不绑在任何界面上，界面重建时可从这里恢复。</p>
 */
public final class VoteLaunchSession {
    private static boolean active;
    private static boolean startConfirmed;
    private static boolean launchConfirmed;
    private static boolean gameActive;

    private static int progress;
    private static int playerCount;
    private static int killerCount;
    private static String mapId = "";
    private static String modeId = "";
    private static String winningMapId = "";

    private VoteLaunchSession() {}

    /** 地图投票结束、上游开始重置地图时调用。 */
    public static void begin(String resolvedMapId) {
        // 已在本局会话中（结算包重发）：只同步 mapId，不清空进度
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
        // 进度只增不减；只有 clear()/新会话会归零。
        progress = Math.max(progress, Mth.clamp(prog, 0, 100));
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
     * 判定点 A：地图重置完成（trueStartGame → STARTING），即将传送。
     *
     * @return true 若本调用首次确认（接收器据此打开全屏转场）
     */
    public static boolean onStartConfirmed(String confirmedMapId) {
        if (!active) {
            return false;
        }
        applyMapId(confirmedMapId);
        if (startConfirmed) {
            return false;
        }
        startConfirmed = true;
        return true;
    }

    /**
     * 判定点 B：环境就绪，转场原地盖「对局开始」章。
     *
     * @return true 若本调用首次将 launchConfirmed 置真
     */
    public static boolean onLaunchConfirmed(String confirmedMapId) {
        if (!active) return false;
        applyMapId(confirmedMapId);
        progress = 100;
        if (launchConfirmed) {
            return false;
        }
        launchConfirmed = true;
        return true;
    }

    public static void onGameActive() {
        if (!active) return;
        gameActive = true;
    }

    public static void onAbort() {
        clear();
    }

    public static void clear() {
        active = false;
        startConfirmed = false;
        launchConfirmed = false;
        gameActive = false;
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

    public static boolean isStartConfirmed() {
        return startConfirmed;
    }

    public static boolean isLaunchConfirmed() {
        return launchConfirmed;
    }

    public static boolean isGameActive() {
        return gameActive;
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
