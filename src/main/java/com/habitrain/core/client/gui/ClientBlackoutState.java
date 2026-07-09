package com.habitrain.core.client.gui;

/**
 * 停电模式客户端状态持有者。
 * <p>
 * 集中管理停电模式在客户端的所有可变状态，消除散布在各渲染类中的 static mutable 字段：
 * <ul>
 *   <li>开局报幕状态（原 BlackoutWelcomeRenderer 中的 roleName/subtitle/goal/welcomeTime）</li>
 *   <li>停电模式激活标志（原 BlackoutHudOverlay / BlackoutVoteState 中各自维护一份）</li>
 * </ul>
 * <p>
 * 各渲染类（BlackoutWelcomeRenderer、BlackoutHudOverlay）通过此类的静态 getter 读取状态，
 * {@link com.habitrain.core.client.HabiTrainCoreClient} 在收到网络包时通过静态 setter 写入状态。
 */
public final class ClientBlackoutState {

    // ========================
    //  开局报幕状态
    // ========================

    private static final int WELCOME_DURATION = 200;
    private static String welcomeRoleName = "";
    private static String welcomeSubtitle = "";
    private static String welcomeGoal = "";
    private static int welcomeTime = 0;

    /** 启动报幕动画 */
    public static void startWelcome(String name, String sub, String g) {
        welcomeRoleName = "§6§l你是 " + name;
        welcomeSubtitle = sub;
        welcomeGoal = g;
        welcomeTime = WELCOME_DURATION;
    }

    public static boolean isWelcomeActive() {
        return welcomeTime > 0;
    }

    public static String getWelcomeRoleName() {
        return welcomeRoleName;
    }

    public static String getWelcomeSubtitle() {
        return welcomeSubtitle;
    }

    public static String getWelcomeGoal() {
        return welcomeGoal;
    }

    public static int getWelcomeTime() {
        return welcomeTime;
    }

    /** 递减报幕计时器 */
    public static void decrementWelcomeTime() {
        if (welcomeTime > 0) welcomeTime--;
    }

    /** 重置报幕状态 */
    public static void resetWelcome() {
        welcomeTime = 0;
        welcomeRoleName = "";
        welcomeSubtitle = "";
        welcomeGoal = "";
    }

    // ========================
    //  停电模式激活标志
    // ========================

    private static boolean blackoutModeActive = false;

    public static void setBlackoutModeActive(boolean v) {
        blackoutModeActive = v;
    }

    public static boolean isBlackoutModeActive() {
        return blackoutModeActive;
    }

    // ========================
    //  全量重置
    // ========================

    public static void resetAll() {
        resetWelcome();
        blackoutModeActive = false;
    }

    private ClientBlackoutState() {
    }
}
