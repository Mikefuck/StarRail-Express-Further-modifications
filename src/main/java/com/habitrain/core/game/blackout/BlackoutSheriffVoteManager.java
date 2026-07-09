package com.habitrain.core.game.blackout;

import com.habitrain.core.network.BlackoutSheriffVotePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BlackoutSheriffVoteManager {
    private static final int VOTE_DURATION_SECONDS = 15;

    private static final Map<ResourceKey<Level>, VoteState> STATES = new HashMap<>();

    private BlackoutSheriffVoteManager() {}

    public record VoteCandidate(UUID playerId, String playerName, int votes) {}

    public record VoteSnapshot(boolean active, int remainingSeconds, int totalSeconds, int sheriffCount, List<VoteCandidate> candidates) {}

    private static final class VoteState {
        int secondsSinceStart = 0;
        boolean started = false;
        boolean finished = false;
        boolean active = false;
        int remainingSeconds = 0;
        int sheriffCount = 1;
        final Map<UUID, List<UUID>> votesByVoter = new HashMap<>();
        final List<UUID> candidateOrder = new ArrayList<>();
    }

    public static void reset(ServerLevel level) {
        STATES.remove(level.dimension());
    }

    public static void onPlayerRemoved(ServerLevel level, UUID playerId) {
        var state = STATES.get(level.dimension());
        if (state == null) return;
        boolean changed = state.votesByVoter.remove(playerId) != null;
        for (List<UUID> targets : state.votesByVoter.values()) {
            changed |= targets.remove(playerId);
        }
        changed |= state.candidateOrder.remove(playerId);
        if (changed && state.active) {
            SheriffVoteBroadcaster.broadcast(level, state.active, state.remainingSeconds, VOTE_DURATION_SECONDS, state.sheriffCount, state.candidateOrder, state.votesByVoter);
        }
    }

    public static void onPlayerJoined(ServerLevel level, ServerPlayer player) {
        var state = STATES.get(level.dimension());
        if (state == null || !state.active) return;
        ServerPlayNetworking.send(player, buildPayload(level, state));
    }

    public static boolean castVote(ServerLevel level, UUID voterId, UUID targetId, int slotIndex) {
        var state = STATES.get(level.dimension());
        if (state == null || !state.active) return false;
        if (!state.candidateOrder.contains(targetId)) return false;
        if (!BlackoutRoleManager.isAlive(level, voterId)) return false;
        if (!state.candidateOrder.contains(voterId)) return false;

        List<UUID> targets = state.votesByVoter.computeIfAbsent(voterId, k -> new ArrayList<>());
        if (slotIndex < 0) {
            targets.removeIf(id -> id.equals(targetId));
        } else if (slotIndex < state.sheriffCount) {
            if (slotIndex < targets.size()) {
                targets.set(slotIndex, targetId);
            } else {
                while (targets.size() < slotIndex) targets.add(null);
                targets.add(targetId);
            }
            targets.removeIf(java.util.Objects::isNull);
        } else {
            return false;
        }
        SheriffVoteBroadcaster.broadcast(level, state.active, state.remainingSeconds, VOTE_DURATION_SECONDS, state.sheriffCount, state.candidateOrder, state.votesByVoter);
        return true;
    }

    private static List<VoteCandidate> buildCandidates(ServerLevel level, VoteState state) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (UUID candidateId : state.candidateOrder) {
            if (BlackoutRoleManager.isAlive(level, candidateId)) {
                counts.put(candidateId, 0);
            }
        }
        for (List<UUID> targets : state.votesByVoter.values()) {
            for (UUID votedId : targets) {
                if (counts.containsKey(votedId)) {
                    counts.put(votedId, counts.get(votedId) + 1);
                }
            }
        }

        Map<UUID, String> nameCache = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            nameCache.put(player.getUUID(), player.getName().getString());
        }

        List<VoteCandidate> candidates = new ArrayList<>();
        for (UUID candidateId : state.candidateOrder) {
            if (!counts.containsKey(candidateId)) continue;
            String name = nameCache.getOrDefault(candidateId, candidateId.toString());
            candidates.add(new VoteCandidate(candidateId, name, counts.get(candidateId)));
        }
        return candidates;
    }

    private static VoteSnapshot buildPayloadSnapshot(ServerLevel level, VoteState state) {
        return new VoteSnapshot(state.active, state.remainingSeconds, VOTE_DURATION_SECONDS,
                state.sheriffCount, buildCandidates(level, state));
    }

    private static BlackoutSheriffVotePayload buildPayload(ServerLevel level, VoteState state) {
        var snapshot = buildPayloadSnapshot(level, state);
        List<BlackoutSheriffVotePayload.Entry> entries = snapshot.candidates().stream()
                .map(candidate -> new BlackoutSheriffVotePayload.Entry(candidate.playerId(), candidate.playerName(), candidate.votes()))
                .toList();
        return new BlackoutSheriffVotePayload(snapshot.active(), snapshot.remainingSeconds(),
                snapshot.totalSeconds(), snapshot.sheriffCount(), entries);
    }
}
