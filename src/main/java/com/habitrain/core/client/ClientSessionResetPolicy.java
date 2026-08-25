package com.habitrain.core.client;

/**
 * Decides which client session caches may be wiped at JOIN / DISCONNECT / game end.
 *
 * <p>{@link com.habitrain.core.game.sre.CustomTaskBlockCache} is a static table shared
 * with the integrated server. Clearing it on JOIN races the scan snapshot and deletes
 * ESP indexes for the host.
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

    /** Dedicated clients must restore {@code habitrain_core.json} after a server sync. */
    public static boolean reloadLocalConfigOnDisconnect(boolean integratedHost) {
        return !integratedHost;
    }
}
