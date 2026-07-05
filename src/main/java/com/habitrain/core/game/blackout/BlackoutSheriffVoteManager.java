package com.habitrain.core.game.blackout;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.network.BlackoutSheriffVotePayload;
import com.habitrain.core.util.SubtitleNotifier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class BlackoutSheriffVoteManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutSheriffVoteManager");
    private static final int VOTE_OPEN_DELAY_SECONDS = 60;
    private static final int VOTE_DURATION_SECONDS = 15;

    private static final Map<ResourceKey<Level>, VoteState> STATES = new HashMap<>();

    private BlackoutSheriffVoteManager() {}

    public record VoteCandidate(UUID playerId, String playerName, int votes) {}

    public record VoteSnapshot(boolean active, int remainingSeconds, int totalSeconds, int sheriffCount, List<VoteCandidate> candidates) {}

    public record VoteResolution(List<UUID> winnerIds, List<String> winnerNames, boolean votedSelection,
                                 List<Boolean> winnerWasKillers, int winnerVotes, String reason) {}

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

    private static VoteState getOrCreate(ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), ignored -> new VoteState());
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
            syncToAll(level);
        }
    }

    public static void onPlayerJoined(ServerLevel level, ServerPlayer player) {
        var state = STATES.get(level.dimension());
        if (state == null || !state.active) return;
        ServerPlayNetworking.send(player, buildPayload(level, state));
    }

    public static Optional<VoteResolution> tickSecond(ServerLevel level) {
        var state = getOrCreate(level);
        if (state.finished) return Optional.empty();

        state.secondsSinceStart++;
        boolean justStarted = false;
        if (!state.started && state.secondsSinceStart >= VOTE_OPEN_DELAY_SECONDS) {
            startVote(level, state);
            justStarted = true;
        }

        if (!state.active || justStarted) return Optional.empty();

        state.remainingSeconds = Math.max(0, state.remainingSeconds - 1);
        syncToAll(level);
        if (state.remainingSeconds > 0) return Optional.empty();

        VoteResolution resolution = resolve(level, state);
        syncToAll(level);
        return Optional.of(resolution);
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
            // 移除该槽位上现有的票
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
        syncToAll(level);
        return true;
    }

    public static boolean isVoteOpen(ServerLevel level) {
        var state = STATES.get(level.dimension());
        return state != null && state.active;
    }

    public static void syncToPlayer(ServerLevel level, ServerPlayer player) {
        var state = STATES.get(level.dimension());
        if (state == null) {
            ServerPlayNetworking.send(player, new BlackoutSheriffVotePayload(false, 0, VOTE_DURATION_SECONDS, 1, List.of()));
            return;
        }
        ServerPlayNetworking.send(player, buildPayload(level, state));
    }

    public static void syncToAll(ServerLevel level) {
        var state = STATES.get(level.dimension());
        if (state == null) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;
        var payload = buildPayload(level, state);
        for (ServerPlayer player : level.players()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static void startVote(ServerLevel level, VoteState state) {
        state.started = true;
        state.active = true;
        state.remainingSeconds = VOTE_DURATION_SECONDS;
        state.votesByVoter.clear();
        state.candidateOrder.clear();

        List<ServerPlayer> alivePlayers = collectAlivePlayers(level);
        for (ServerPlayer player : alivePlayers) {
            state.candidateOrder.add(player.getUUID());
        }

        int divisor = ConfigManager.getInstance().getSheriffCountDivisor();
        state.sheriffCount = Math.max(0, alivePlayers.size() / Math.max(1, divisor));

        if (state.candidateOrder.isEmpty() || state.sheriffCount == 0) {
            state.active = false;
            state.finished = true;
            broadcast(level, "§e当前存活玩家数不足以选出警长（需要至少 " + divisor + " 人，当前 " + alivePlayers.size() + " 人）。");
            return;
        }

        broadcast(level, "§e警长投票已开启，本局将选出 §b" + state.sheriffCount + " §e名警长。按绑定键打开投票页面。");
        syncToAll(level);
        LOGGER.info("Sheriff vote started in {} with {} candidates, sheriffCount={}",
                level.dimension().location(), state.candidateOrder.size(), state.sheriffCount);
    }

    private static VoteResolution resolve(ServerLevel level, VoteState state) {
        state.active = false;
        state.finished = true;

        List<VoteCandidate> candidates = buildCandidates(level, state);
        if (candidates.isEmpty()) {
            broadcast(level, "§e警长投票结束，但当前没有可用候选人。");
            return new VoteResolution(List.of(), List.of(), false, List.of(), 0, "no_candidates");
        }

        boolean viaVote = state.votesByVoter.values().stream().anyMatch(targets -> !targets.isEmpty());

        int sheriffCount = state.sheriffCount;
        List<UUID> winnerIds = new ArrayList<>();
        List<String> winnerNames = new ArrayList<>();
        List<Boolean> winnerWasKillers = new ArrayList<>();
        int winnerVotes = 0;
        String reason;

        if (viaVote) {
            Map<UUID, Integer> voteCounts = new LinkedHashMap<>();
            for (VoteCandidate candidate : candidates) {
                voteCounts.put(candidate.playerId(), 0);
            }
            for (List<UUID> targets : state.votesByVoter.values()) {
                for (UUID votedId : targets) {
                    voteCounts.computeIfPresent(votedId, (ignored, count) -> count + 1);
                }
            }

            winnerVotes = voteCounts.values().stream().max(Integer::compareTo).orElse(0);

            // 按票数降序排序，取 Top-N
            List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(voteCounts.entrySet());
            sorted.sort((a, b) -> {
                int cmp = Integer.compare(b.getValue(), a.getValue());
                if (cmp != 0) return cmp;
                // 平票时随机排序（用 level 的随机源稍后处理）
                return 0;
            });

            java.util.Random random = new java.util.Random(level.getRandom().nextLong());
            // 取 Top-N，处理平票：把并列末尾的候选人随机选
            int idx = 0;
            while (winnerIds.size() < sheriffCount && idx < sorted.size()) {
                int currentVotes = sorted.get(idx).getValue();
                List<UUID> tied = new ArrayList<>();
                while (idx < sorted.size() && sorted.get(idx).getValue() == currentVotes) {
                    tied.add(sorted.get(idx).getKey());
                    idx++;
                }
                int remaining = sheriffCount - winnerIds.size();
                if (tied.size() <= remaining) {
                    winnerIds.addAll(tied);
                } else {
                    // 随机选 remaining 个
                    Collections.shuffle(tied, random);
                    winnerIds.addAll(tied.subList(0, remaining));
                }
            }
            reason = "vote_topn";
        } else {
            // 无人投票：随机指定 sheriffCount 名好人（若好人不足则从所有人中选）
            java.util.Random random = new java.util.Random(level.getRandom().nextLong());
            List<UUID> goodCandidates = candidates.stream()
                    .filter(c -> BlackoutRoleManager.getFaction(level, c.playerId()) != BlackoutRoleManager.Faction.BAD)
                    .map(VoteCandidate::playerId)
                    .toList();
            List<UUID> pool = goodCandidates.isEmpty()
                    ? candidates.stream().map(VoteCandidate::playerId).toList()
                    : goodCandidates;
            Collections.shuffle(new ArrayList<>(pool), random);
            int count = Math.min(sheriffCount, pool.size());
            winnerIds = new ArrayList<>(pool.subList(0, count));
            reason = goodCandidates.isEmpty() ? "random_any" : "random_non_killer";
        }

        for (UUID winnerId : winnerIds) {
            String name = findName(level, winnerId);
            winnerNames.add(name);
            winnerWasKillers.add(BlackoutRoleManager.getFaction(level, winnerId) == BlackoutRoleManager.Faction.BAD);
        }

        if (viaVote && !winnerIds.isEmpty()) {
            StringBuilder sb = new StringBuilder("§a警长投票结束，当选警长：");
            for (int i = 0; i < winnerNames.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append("§e").append(winnerNames.get(i)).append("§a");
            }
            broadcast(level, sb.toString());
        } else if (!viaVote && !winnerIds.isEmpty()) {
            StringBuilder sb = new StringBuilder("§e无人投票，系统随机指定 §b");
            for (int i = 0; i < winnerNames.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(winnerNames.get(i));
            }
            sb.append(" §e为警长。");
            broadcast(level, sb.toString());
        }

        return new VoteResolution(winnerIds, winnerNames, viaVote, winnerWasKillers, winnerVotes, reason);
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

    private static List<ServerPlayer> collectAlivePlayers(ServerLevel level) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (BlackoutRoleManager.isAlive(level, player.getUUID())) {
                players.add(player);
            }
        }
        players.sort((a, b) -> a.getName().getString().compareToIgnoreCase(b.getName().getString()));
        return players;
    }

    private static String findName(ServerLevel level, UUID playerId) {
        for (ServerPlayer player : level.players()) {
            if (player.getUUID().equals(playerId)) {
                return player.getName().getString();
            }
        }
        return playerId.toString();
    }

    private static void broadcast(ServerLevel level, String message) {
        Component component = Component.literal(message);
        for (ServerPlayer player : level.players()) {
            SubtitleNotifier.sendTop(player, Component.empty(), component, 80);
        }
    }
}