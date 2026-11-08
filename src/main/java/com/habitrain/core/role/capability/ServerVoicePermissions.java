package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.MinecraftServer;
import java.util.HashMap;
import java.util.UUID;

/** World access belongs exclusively to the server thread; UDP readers see one immutable revision. */
public final class ServerVoicePermissions {
    private static volatile VoicePermissionSnapshot current = VoicePermissionSnapshot.EMPTY;
    private static volatile boolean accepting = true;
    private ServerVoicePermissions() {}
    public static VoicePermissionSnapshot current() { return current; }
    public static void clear() { current = VoicePermissionSnapshot.EMPTY; }
    public static void start() { clear(); accepting = true; }
    public static void stop() { accepting = false; clear(); }
    public static void remove(UUID playerId) {
        var previous = current;
        var players = new HashMap<>(previous.players());
        players.remove(playerId);
        current = new VoicePermissionSnapshot(players, previous.policies());
    }
    public static void refresh(MinecraftServer server) {
        if (!accepting) return;
        if (!server.isSameThread()) throw new IllegalStateException("Voice snapshot requires server thread");
        try {
            publish(server);
        } catch (RuntimeException failure) {
            clear();
            com.habitrain.core.HabiTrainCore.LOGGER.error("Cannot publish voice permission snapshot", failure);
        }
    }
    private static void publish(MinecraftServer server) {
        var capability = RoleCapabilityApi.instance();
        if (!(capability instanceof RoleCapabilityServiceImpl service)) { clear(); return; }
        var players = new HashMap<UUID, VoicePermissionSnapshot.Participant>();
        for (var player : server.getPlayerList().getPlayers()) {
            try {
                var game = SREGameWorldComponent.KEY.get(player.serverLevel());
                var role = game.getRole(player);
                RoleKey key = role == null ? null : RoleCatalogApi.instance().canonicalize(role.identifier());
                players.put(player.getUUID(), new VoicePermissionSnapshot.Participant(key,
                        service.groupOf(player.getUUID()), player.serverLevel().dimension().location().toString(),
                        player.getX(), player.getY(), player.getZ()));
            } catch (RuntimeException failure) {
                com.habitrain.core.HabiTrainCore.LOGGER.warn("Cannot refresh voice permissions for {}", player.getUUID(), failure);
            }
        }
        current = new VoicePermissionSnapshot(players, service.enabledVoicePolicies());
    }
}
