package com.habitrain.core.game.blackout;

/**
 * Pure join/disconnect policy for blackout grace. No Minecraft types.
 *
 * <p>Upstream {@code PlayerDiscard} force-kills {@code DISCONNECT} on
 * {@code PlayerList.remove}; {@code PlayerJoinUtils.adjustPlayerPosition}
 * spectates every {@code ACTIVE} join. Mixins consult this helper so the
 * 60s grace can survive those paths.</p>
 */
public final class DisconnectGracePolicy {

    private DisconnectGracePolicy() {}

    /**
     * Whether to cancel an upstream {@code DISCONNECT} {@code killPlayer}.
     *
     * @param graceEnabled {@code BlackoutMode.DISCONNECT_GRACE_TICKS > 0}
     * @param blackoutAlive still present in {@code BlackoutRoleManager} alive table
     */
    public static boolean shouldSkipDisconnectKill(boolean graceEnabled, boolean blackoutAlive) {
        return graceEnabled && blackoutAlive;
    }

    /**
     * Whether a joining player should be put into spectator.
     *
     * <ul>
     *   <li>{@code blackoutAlive} (still in the alive table) → never spectator (grace reconnect)</li>
     *   <li>{@code STARTING} / {@code INITIATING} → spectator unless in the forced-ready snapshot
     *       or blackout-alive</li>
     *   <li>{@code ACTIVE} → spectator unless blackout-alive</li>
     *   <li>{@code INACTIVE} / other → do not spectator</li>
     * </ul>
     */
    public static boolean shouldSpectatorOnJoin(String statusName, boolean blackoutAlive,
                                               boolean inForcedReadySnapshot) {
        if (blackoutAlive) {
            return false;
        }
        if (isStatus(statusName, "STARTING") || isStatus(statusName, "INITIATING")) {
            return !inForcedReadySnapshot;
        }
        if (isStatus(statusName, "ACTIVE")) {
            return true;
        }
        return false;
    }

    private static boolean isStatus(String statusName, String expected) {
        return expected.equalsIgnoreCase(statusName);
    }
}
