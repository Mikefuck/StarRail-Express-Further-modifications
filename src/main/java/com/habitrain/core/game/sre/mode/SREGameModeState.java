package com.habitrain.core.game.sre.mode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service object holding SRE game mode state that was previously static mutable fields.
 * Managed implicitly per MinecraftServer lifecycle (created on class load, lives for server lifetime).
 * <p>
 * Convergence target for: LOBBY_GROUP, pendingVoiceJoins, pendingGameEndGroupJoin,
 * builtinTasksRegistered, sreEventsRegistered.
 * <p>
 * {@code lobbyGroup} is stored as {@link Object} to avoid a strong field descriptor reference to
 * optional {@code de.maxhenkel.voicechat.api.Group} when Simple Voice Chat is not installed.
 */
public class SREGameModeState {

    private Object lobbyGroup;
    private final Map<UUID, Integer> pendingVoiceJoins = new ConcurrentHashMap<>();
    private boolean pendingGameEndGroupJoin = false;
    private boolean builtinTasksRegistered = false;
    private boolean sreEventsRegistered = false;

    // ========== Lobby Group ==========

    public Object getLobbyGroup() {
        return lobbyGroup;
    }

    public void setLobbyGroup(Object lobbyGroup) {
        this.lobbyGroup = lobbyGroup;
    }

    // ========== Pending Voice Joins ==========

    public Map<UUID, Integer> getPendingVoiceJoins() {
        return pendingVoiceJoins;
    }

    // ========== Game End Group Join ==========

    public boolean isPendingGameEndGroupJoin() {
        return pendingGameEndGroupJoin;
    }

    public void setPendingGameEndGroupJoin(boolean pending) {
        this.pendingGameEndGroupJoin = pending;
    }

    // ========== Registration Guards ==========

    public boolean isBuiltinTasksRegistered() {
        return builtinTasksRegistered;
    }

    public void setBuiltinTasksRegistered(boolean registered) {
        this.builtinTasksRegistered = registered;
    }

    public boolean isSreEventsRegistered() {
        return sreEventsRegistered;
    }

    public void setSreEventsRegistered(boolean registered) {
        this.sreEventsRegistered = registered;
    }

    public void resetServerState() {
        this.lobbyGroup = null;
        this.pendingVoiceJoins.clear();
        this.pendingGameEndGroupJoin = false;
    }
}
