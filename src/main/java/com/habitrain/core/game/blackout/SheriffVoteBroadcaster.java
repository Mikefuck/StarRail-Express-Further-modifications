package com.habitrain.core.game.blackout;

import com.habitrain.core.network.BlackoutSheriffVotePayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class SheriffVoteBroadcaster {
    private static int lastPayloadHash = 0;

    static void tickSecond(ServerLevel level, boolean active, int windowSeconds, int totalSeconds, int sheriffCount, List<UUID> candidateOrder, Map<UUID, List<UUID>> votesByVoter) {
        if (!active) return;

        int hash = computeHash(active, windowSeconds, candidateOrder, votesByVoter);
        if (hash == lastPayloadHash) return;

        lastPayloadHash = hash;
        broadcast(level, active, windowSeconds, totalSeconds, sheriffCount, candidateOrder, votesByVoter);
    }

    private static int computeHash(boolean active, int windowSeconds, List<UUID> candidateOrder, Map<UUID, List<UUID>> votesByVoter) {
        int h = active ? 1 : 0;
        h = 31 * h + windowSeconds;
        h = 31 * h + candidateOrder.hashCode();
        for (Map.Entry<UUID, List<UUID>> e : votesByVoter.entrySet()) {
            h = 31 * h + e.getKey().hashCode();
            h = 31 * h + e.getValue().hashCode();
        }
        return h;
    }

    static void broadcast(ServerLevel level, boolean active, int windowSeconds, int totalSeconds, int sheriffCount, List<UUID> candidateOrder, Map<UUID, List<UUID>> votesByVoter) {
        Map<UUID, Integer> voteCounts = new HashMap<>();
        for (UUID id : candidateOrder) {
            voteCounts.put(id, 0);
        }
        for (List<UUID> targets : votesByVoter.values()) {
            for (UUID votedId : targets) {
                voteCounts.computeIfPresent(votedId, (k, v) -> v + 1);
            }
        }

        Map<UUID, String> names = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            names.put(player.getUUID(), player.getName().getString());
        }

        List<BlackoutSheriffVotePayload.Entry> entries = new ArrayList<>();
        for (UUID id : candidateOrder) {
            Integer count = voteCounts.get(id);
            if (count == null) continue;
            String name = names.getOrDefault(id, id.toString());
            entries.add(new BlackoutSheriffVotePayload.Entry(id, name, count));
        }

        BlackoutSheriffVotePayload.broadcastToAll(level.getServer(), active, windowSeconds, totalSeconds, sheriffCount, entries);
    }

}
