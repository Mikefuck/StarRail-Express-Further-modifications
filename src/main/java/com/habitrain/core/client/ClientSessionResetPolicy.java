package com.habitrain.core.client;


/**
 * Decides which client session caches may be wiped at JOIN / DISCONNECT / game end.
 *
 * <p>Client indexes are independent of the server index. INIT clears the old
 * connection before snapshots arrive; JOIN must preserve those snapshots.
 */
public final class ClientSessionResetPolicy {

    private ClientSessionResetPolicy() {}

    public static boolean clearEspCachesOnJoin(boolean integratedHost) {
        return false;
    }

    public static boolean clearEspCachesOnDisconnect(boolean integratedHost) {
        return true;
    }

    public static boolean clearEspCachesOnGameFinished() {
        return true;
    }

    /** Menu permissions, role mirrors and handshake belong to the connection, not a round. */
    public static boolean clearConnectionState(boolean matchFinished) {
        return !matchFinished;
    }

    /** Dedicated clients must restore {@code habitrain_core.json} after a server sync. */
    public static boolean reloadLocalConfigOnDisconnect(boolean integratedHost) {
        return !integratedHost;
    }
}
