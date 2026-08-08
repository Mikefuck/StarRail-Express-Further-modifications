package com.habitrain.core.client.gui;

/**
 * 对局结束结算画面（GameEndTransitionScreen）的客户端静态状态。
 * <p>
 * 用于在结算画面播放期间屏蔽其它 HUD/交互，并在画面结束后保留一段
 * 宽限期（grace）防止残留状态闪回。
 */
public final class GameEndOverlayState {
    private static boolean active = false;
    private static long blockUntilMillis = Long.MIN_VALUE;

    private GameEndOverlayState() {}

    public static boolean isActive() {
        return active;
    }

    public static boolean isBlockingNow() {
        return active || System.currentTimeMillis() < blockUntilMillis;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static void scheduleGrace(long millis) {
        blockUntilMillis = System.currentTimeMillis() + Math.max(0, millis);
        active = false;
    }
}
