package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.network.replay.ReplayTracks;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.replay.ReplayMapCapture;
import com.habitrain.core.scene.server.SceneBandwidthGovernor;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static com.habitrain.core.network.replay.ReplayPackets.*;

public final class ReplayBoardService {
    public static final int VIEW_DISTANCE = 64;
    private static final long PER_PLAYER = 96 * 1024L, GLOBAL = 512 * 1024L;
    private static final int MAX_PENDING = 64;
    private static final SceneBandwidthGovernor BANDWIDTH = new SceneBandwidthGovernor(System::nanoTime, GLOBAL);
    private static final Map<UUID, Transfer> TRANSFERS = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    private static final LinkedHashMap<String, ReplayMapCapture.Result> MAP_CACHE = new LinkedHashMap<>(8, .75f, true);
    private static ThreadPoolExecutor worker;
    private static MinecraftServer server;
    private static Published latest;
    private record Published(LastMatchSummaryS2C packet, int bytes, ReplayMapCapture.Result map) {}

    private ReplayBoardService() {}

    public static void register() {
        MatchReplayRecorder.register();
        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "habitrain-replay-encode"); thread.setDaemon(true); return thread;
            });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            MatchReplayRecorder.reset();
            server = null;
            latest = null;
            TRANSFERS.clear();
            LAST_REQUEST.clear();
            BANDWIDTH.reset();
            if (worker != null) worker.shutdownNow();
            worker = null;
            synchronized (MAP_CACHE) { MAP_CACHE.clear(); }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> announce(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            UUID id = handler.player.getUUID();
            TRANSFERS.remove(id); LAST_REQUEST.remove(id); BANDWIDTH.forget(id);
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((p, old, next) -> {
            TRANSFERS.remove(p.getUUID());
            announce(p);
        });
        ServerTickEvents.END_SERVER_TICK.register(ReplayBoardService::tick);
    }

    public static CompletableFuture<ReplayMapCapture.Result> encodeMap(ReplayMapCapture.Raw raw) {
        ThreadPoolExecutor executor = worker;
        if (executor == null) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, ReplayMapCapture.Result> recent;
                synchronized (MAP_CACHE) { recent = Map.copyOf(MAP_CACHE); }
                try {
                    var result = ReplayMapCapture.encode(raw, recent);
                    synchronized (MAP_CACHE) {
                        if (!executor.isShutdown()) {
                            MAP_CACHE.put(result.manifest().hash(), result);
                            long bytes = MAP_CACHE.values().stream().mapToLong(ReplayMapCapture.Result::bytes).sum();
                            while (MAP_CACHE.size() > 3 || bytes > 48L * 1024 * 1024) bytes -= MAP_CACHE.pollFirstEntry().getValue().bytes();
                        }
                    }
                    return result;
                } catch (IOException | RuntimeException failure) {
                    LoggerFactory.getLogger(ReplayBoardService.class).warn("Could not encode replay map {}", raw.map(), failure);
                    return null;
                }
            }, executor);
        } catch (RejectedExecutionException ignored) {
            return CompletableFuture.completedFuture(null);
        }
    }

    public static void publish(LastMatchReplay summary, CompletableFuture<ReplayMapCapture.Result> map) {
        MinecraftServer owner = server;
        if (owner == null) return;
        var data = summary.participants().stream().map(LastMatchReplay.Participant::track).toList();
        var encoded = ReplayTracks.encodeWithStatus(data);
        byte[] tracks = encoded.bytes();
        var resolved = map == null ? null : map.getNow(null);
        publishRevision(summary, tracks, resolved, true, encoded.thinned());
        if (map != null && !map.isDone()) map.thenAccept(result -> {
            if (result != null) owner.execute(() -> {
                if (server == owner && latest != null && latest.packet.replay().roundId().equals(summary.roundId())) {
                    publishRevision(summary, tracks, result, false, encoded.thinned());
                }
            });
        });
        else if (map != null && resolved == null) {
            // The future may have completed between getNow and isDone.
            var result = map.getNow(null);
            if (result != null) publishRevision(summary, tracks, result, false, encoded.thinned());
        }
    }

    private static void publishRevision(LastMatchReplay s, byte[] tracks, ReplayMapCapture.Result map, boolean newRound, boolean tracksThinned) {
        if (newRound) TRANSFERS.clear();
        var people = s.participants().stream().map(p -> new LastMatchReplay.Participant(p.id(), p.name(),
                p.startRole(), p.startColor(), p.endRole(), p.endColor(), p.faction(), p.winner(),
                p.survivalSeconds(), p.kills(), p.score(), p.itemUses(), List.of())).toList();
        var replay = new LastMatchReplay(s.roundId(), s.playbackAnchorTick(), s.durationTicks(), s.mapName(), s.modeId(),
                s.winStatus(), s.winKind(), s.customWinId(), s.customTitleJson(), s.customSubtitleJson(), s.customColor(),
                s.origin(), map == null ? null : map.manifest(), people, s.events(), s.spans(),
                s.mvp(), s.killCount(), s.limited() || tracksThinned);
        var packet = new LastMatchSummaryS2C(replay, tracks);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            LastMatchSummaryS2C.CODEC.encode(buffer, packet);
            latest = new Published(packet, buffer.readableBytes(), map);
        } finally {
            buffer.release();
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) announce(p);
    }

    private static void announce(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, Available.TYPE)) {
            ServerPlayNetworking.send(player, new Available(latest == null ? EMPTY : latest.packet.replay().roundId(),
                    latest != null && latest.map != null));
        }
    }

    public static void request(ServerPlayer p, RequestSummary packet) {
        if (!allow(p, packet.board()) || !ServerPlayNetworking.canSend(p, LastMatchSummaryS2C.TYPE)) return;
        if (latest == null || !latest.packet.replay().roundId().equals(packet.roundId())) {
            announce(p);
            return;
        }
        if (TRANSFERS.size() >= MAX_PENDING || TRANSFERS.containsKey(p.getUUID())) return;
        TRANSFERS.put(p.getUUID(), new Transfer(packet.board(), latest, null));
    }

    public static void request(ServerPlayer p, ReplayMissingSectionsC2S packet) {
        if (!allow(p, packet.board()) || !ServerPlayNetworking.canSend(p, ReplaySectionPatchS2C.TYPE)) return;
        Published source = latest;
        BitSet bits = packet.missing();
        if (source == null || source.map == null || !source.packet.replay().roundId().equals(packet.roundId())
                || bits.isEmpty() || bits.cardinality() > MAX_REQUESTED || bits.length() > source.map.patches().size()
                || TRANSFERS.size() >= MAX_PENDING || TRANSFERS.containsKey(p.getUUID())) return;
        TRANSFERS.put(p.getUUID(), new Transfer(packet.board(), source, bits.stream().toArray()));
    }

    private static boolean allow(ServerPlayer p, BlockPos board) {
        if (server == null || server != p.getServer()) return false;
        long tick = server.overworld().getGameTime();
        Long previous = LAST_REQUEST.get(p.getUUID());
        if (previous != null && tick - previous < 10) return false;
        LAST_REQUEST.put(p.getUUID(), tick);
        return accessible(p, board);
    }

    private static boolean accessible(ServerPlayer p, BlockPos board) {
        if (p.distanceToSqr(board.getX() + .5, board.getY() + .5, board.getZ() + .5) > VIEW_DISTANCE * VIEW_DISTANCE
                || p.serverLevel().isOutsideBuildHeight(board)) return false;
        var chunk = p.serverLevel().getChunkSource().getChunkNow(board.getX() >> 4, board.getZ() >> 4);
        return chunk != null && chunk.getBlockState(board).is(SettlementSummaryBoard.BLOCK);
    }

    private static void tick(MinecraftServer s) {
        if (TRANSFERS.isEmpty()) return;
        var iterator = TRANSFERS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var t = entry.getValue();
            ServerPlayer player = s.getPlayerList().getPlayer(entry.getKey());
            if (player == null || latest == null || !latest.packet.replay().roundId().equals(t.round.packet.replay().roundId())
                    || !accessible(player, t.board)) {
                iterator.remove();
                continue;
            }
            if (t.ready == null) {
                int size = t.indices == null ? t.round.bytes : Math.min(FRAGMENT_BYTES, t.patch().length - t.offset) + 32;
                t.ready = BANDWIDTH.reserve(entry.getKey(), size, PER_PLAYER);
            }
            if (BANDWIDTH.nowNanos() - t.ready < 0) continue;
            if (t.indices == null) {
                ServerPlayNetworking.send(player, t.round.packet);
                iterator.remove();
                continue;
            }
            byte[] patch = t.patch();
            int end = Math.min(t.offset + FRAGMENT_BYTES, patch.length);
            ServerPlayNetworking.send(player, new ReplaySectionPatchS2C(t.round.packet.replay().roundId(),
                    t.indices[t.cursor], t.offset, patch.length, Arrays.copyOfRange(patch, t.offset, end)));
            t.ready = null;
            t.offset = end;
            if (end == patch.length) {
                t.offset = 0;
                if (++t.cursor == t.indices.length) iterator.remove();
            }
        }
    }

    private static final class Transfer {
        final BlockPos board;
        final Published round;
        final int[] indices;
        Long ready;
        int cursor, offset;
        Transfer(BlockPos board, Published round, int[] indices) {
            this.board = board.immutable(); this.round = round; this.indices = indices;
        }
        byte[] patch() { return round.map.patches().get(indices[cursor]); }
    }
}
