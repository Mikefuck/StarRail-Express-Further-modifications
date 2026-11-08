package com.habitrain.core.game.sre.match;

import java.util.UUID;

/** One round per server instance, including voting and loading before SRE becomes ACTIVE. */
public final class SingleMatchGate {
    public enum Phase { LOBBY, VOTING, LOADING, STARTING, RUNNING, SETTLING, CLEANUP }
    public record Snapshot(UUID serverId, long roundId, String dimension, Phase phase) {}
    private UUID serverId = UUID.randomUUID();
    private long sequence;
    private Snapshot snapshot = new Snapshot(serverId, 0, "", Phase.LOBBY);
    private boolean nativeClaimed;
    private boolean nativeConfirmed;

    public synchronized Snapshot snapshot() { return snapshot; }
    public synchronized boolean isNativeMatch() { return nativeClaimed; }
    public synchronized boolean canStartRequest(String dimension) {
        return snapshot.phase() == Phase.LOBBY || owns(dimension)
                && (snapshot.phase() == Phase.VOTING || snapshot.phase() == Phase.LOADING
                || snapshot.phase() == Phase.STARTING && !nativeClaimed);
    }
    public synchronized void reset() {
        serverId = UUID.randomUUID();
        nativeClaimed = nativeConfirmed = false;
        snapshot = new Snapshot(serverId, ++sequence, "", Phase.LOBBY);
    }
    public synchronized boolean vote(String dimension) {
        if (snapshot.phase() != Phase.LOBBY) return false;
        begin(dimension, Phase.VOTING);
        return true;
    }
    public synchronized boolean load(String dimension) {
        if (snapshot.phase() == Phase.LOBBY) begin(dimension, Phase.LOADING);
        else if (owns(dimension) && snapshot.phase() == Phase.VOTING) phase(Phase.LOADING);
        else return false;
        return true;
    }
    public synchronized boolean start(String dimension) {
        if (snapshot.phase() == Phase.LOBBY) begin(dimension, Phase.STARTING);
        else if (owns(dimension) && (snapshot.phase() == Phase.VOTING || snapshot.phase() == Phase.LOADING)) phase(Phase.STARTING);
        else return false;
        return true;
    }
    public synchronized boolean nativeStart(String dimension) {
        if (nativeClaimed) return false;
        if (snapshot.phase() != Phase.STARTING || !owns(dimension)) {
            if (!start(dimension)) return false;
        }
        nativeClaimed = true;
        return true;
    }
    /** trueStartGame may also be called directly by an upstream mode. */
    public synchronized boolean nativeConfirm(String dimension) {
        if (nativeConfirmed) return false;
        if (!nativeClaimed && !nativeStart(dimension)) return false;
        if (!owns(dimension) || snapshot.phase() != Phase.STARTING) return false;
        nativeConfirmed = true;
        return true;
    }
    public synchronized boolean owns(String dimension) {
        return snapshot.phase() != Phase.LOBBY && snapshot.dimension().equals(dimension);
    }
    public synchronized void transition(String dimension, Phase phase) {
        if (owns(dimension)) phase(phase);
    }
    public synchronized void releaseVote(String dimension) {
        if (owns(dimension) && snapshot.phase() == Phase.VOTING) release(dimension);
    }
    /** A cancelled session must never release a replacement round created by its callback. */
    public synchronized void releaseVote(Snapshot reservation) {
        if (reservation != null && snapshot.serverId().equals(reservation.serverId())
                && snapshot.roundId() == reservation.roundId()) releaseVote(reservation.dimension());
    }
    public synchronized void abortPreparing(String dimension) {
        if (owns(dimension) && (snapshot.phase() == Phase.VOTING || snapshot.phase() == Phase.LOADING
                || snapshot.phase() == Phase.STARTING)) release(dimension);
    }
    public synchronized void release(String dimension) {
        if (!owns(dimension)) return;
        phase(Phase.CLEANUP);
        nativeClaimed = nativeConfirmed = false;
        snapshot = new Snapshot(serverId, snapshot.roundId(), "", Phase.LOBBY);
    }
    private void begin(String dimension, Phase phase) {
        snapshot = new Snapshot(serverId, ++sequence, dimension, phase);
    }
    private void phase(Phase phase) {
        snapshot = new Snapshot(serverId, snapshot.roundId(), snapshot.dimension(), phase);
    }
}
