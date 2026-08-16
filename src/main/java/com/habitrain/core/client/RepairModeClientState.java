package com.habitrain.core.client;

/**
 * 客户端本机玩家维修模式状态（由服务端 {@link com.habitrain.core.network.RepairModeSyncPayload}
 * 同步，仅影响本机渲染/界面决策，不参与任何服务端判定）。
 */
public final class RepairModeClientState {
    private static boolean repairing = false;

    private RepairModeClientState() {}

    public static void setRepairing(boolean value) {
        repairing = value;
    }

    /** 本机玩家当前是否处于维修模式。 */
    public static boolean isLocalRepairer() {
        return repairing;
    }

    /** 换服/断线时清除上一连接的权限状态。 */
    public static void reset() {
        repairing = false;
    }
}
