package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.ReplayPackets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Takes only geometry, never block entity NBT, and never synchronously loads a chunk. */
public final class ReplayMapCapture {
    private static final int TICK_BUDGET_NS = 2_000_000;
    private static final int MAX_TICKETS = 8;
    private static final long TIMEOUT_TICKS = 200;
    private static final TicketType<ChunkPos> TICKET = TicketType.create("habitrain_replay_map",
            Comparator.comparingLong(ChunkPos::toLong));

    private final ServerLevel level;
    private final SceneBounds clip;
    private final String map;
    private final long deadline;
    private final ArrayDeque<ChunkWork> pending = new ArrayDeque<>();
    private final Set<ChunkPos> tickets = new HashSet<>();
    private final List<ReplaySectionGeometry.Snapshot> snapshots = new ArrayList<>();
    private final Set<Long> remaining = new HashSet<>();
    private final Map<Long, PalettedContainer<BlockState>> original = new HashMap<>();
    private boolean failed;

    private static final class ChunkWork {
        final ChunkPos pos;
        int y;
        ChunkWork(ChunkPos pos, int y) { this.pos = pos; this.y = y; }
    }

    public ReplayMapCapture(ServerLevel level, SceneBounds clip, String map) {
        this.level = level;
        this.clip = clip;
        this.map = map;
        this.deadline = level.getGameTime() + TIMEOUT_TICKS;
        if (!ReplaySectionGeometry.supported(clip)) {
            failed = true;
            return;
        }
        for (int x = clip.minSectionX(); x <= clip.maxSectionX(); x++) {
            for (int z = clip.minSectionZ(); z <= clip.maxSectionZ(); z++) {
                pending.addLast(new ChunkWork(new ChunkPos(x, z), clip.minSectionY()));
                for (int y = clip.minSectionY(); y <= clip.maxSectionY(); y++) remaining.add(SectionPos.asLong(x, y, z));
            }
        }
    }

    /** One section is atomic; the deadline is checked before starting the next section. */
    public boolean step() {
        if (pending.isEmpty()) return true;
        if (level.getGameTime() >= deadline) return true;
        long end = System.nanoTime() + TICK_BUDGET_NS;
        int misses = pending.size();
        while (!pending.isEmpty() && System.nanoTime() - end < 0 && misses > 0) {
            ChunkWork work = pending.removeFirst();
            long key = SectionPos.asLong(work.pos.x, work.y, work.pos.z);
            var frozen = original.get(key);
            LevelChunk chunk = frozen == null ? level.getChunkSource().getChunkNow(work.pos.x, work.pos.z) : null;
            if (chunk == null && frozen == null) {
                if (tickets.size() < MAX_TICKETS && tickets.add(work.pos)) {
                    level.getChunkSource().addRegionTicket(TICKET, work.pos, 0, work.pos);
                }
                pending.addLast(work);
                misses--;
                continue;
            }
            remaining.remove(key);
            original.remove(key);
            var section = frozen != null ? ReplaySectionGeometry.capture(work.pos.x, work.y, work.pos.z, frozen, clip)
                    : ReplaySectionGeometry.capture(chunk, work.y, clip);
            work.y++;
            if (section != null) snapshots.add(section);
            if (work.y <= clip.maxSectionY()) pending.addFirst(work);
            else release(work.pos);
            misses = pending.size();
        }
        return pending.isEmpty();
    }

    /** Preserve a not-yet-scanned section on the first in-round block write. No serialization in the mutation hook. */
    public void beforeWrite(LevelChunk chunk, BlockPos pos) {
        if (!clip.contains(pos)) return;
        long key = SectionPos.asLong(pos);
        if (!remaining.contains(key) || original.containsKey(key)) return;
        int i = chunk.getSectionIndex(pos.getY());
        if (i >= 0 && i < chunk.getSections().length) original.put(key, chunk.getSections()[i].getStates().copy());
    }

    private void release(ChunkPos pos) {
        if (tickets.remove(pos)) level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
    }

    public void cancel() {
        failed = true;
        releaseCapture();
    }

    private void releaseCapture() {
        pending.clear();
        remaining.clear();
        original.clear();
        snapshots.clear();
        for (ChunkPos pos : List.copyOf(tickets)) release(pos);
    }

    /** Seal the geometry already captured, without reading chunks after the round ends. */
    public @Nullable Raw finish() {
        try {
            if (failed || snapshots.isEmpty()) return null;
            // Keep the play area's scale even if we captured only a small fragment.
            SceneBounds partialBounds = pending.isEmpty() ? null : clip;
            return new Raw(map, level.dimension().location().toString(), List.copyOf(snapshots), partialBounds);
        } finally {
            releaseCapture();
        }
    }

    public record Raw(String map, String dimension, List<ReplaySectionGeometry.Snapshot> sections,
                      @Nullable SceneBounds partialBounds) {}
    public record Result(ReplayMapManifest manifest, List<byte[]> patches, long bytes) {}

    /** The compressed blobs are shared by every viewer and reused if a later capture matches. */
    public static Result encode(Raw raw, Map<String, Result> recent) throws IOException {
        SceneBounds bounds = raw.partialBounds;
        if (raw.sections.isEmpty()) throw new IOException("Empty replay map");
        if (bounds == null) {
            for (var s : raw.sections) {
                SceneBounds p = s.occupied();
                bounds = bounds == null ? p : new SceneBounds(Math.min(bounds.minX(), p.minX()), Math.min(bounds.minY(), p.minY()),
                        Math.min(bounds.minZ(), p.minZ()), Math.max(bounds.maxX(), p.maxX()), Math.max(bounds.maxY(), p.maxY()),
                        Math.max(bounds.maxZ(), p.maxZ()));
            }
        }
        var sections = new ArrayList<>(raw.sections);
        sections.sort(Comparator.comparingInt((ReplaySectionGeometry.Snapshot s) -> s.data().relX)
                .thenComparingInt(s -> s.data().relY).thenComparingInt(s -> s.data().relZ));
        var entries = new ArrayList<ReplayMapManifest.Entry>(sections.size());
        var relative = new ArrayList<SceneAssetCodec.SectionData>(sections.size());
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        digest.update(("replay-v1\n" + raw.map + "\n" + raw.dimension + "\n" + bounds + "\n").getBytes(StandardCharsets.UTF_8));
        for (var s : sections) {
            var rel = ReplaySectionGeometry.relative(s.data(), bounds);
            var pos = new ReplayMapManifest.Section(rel.relX, rel.relY, rel.relZ);
            entries.add(new ReplayMapManifest.Entry(pos, s.crc()));
            digest.update((pos + ":" + s.crc() + "\n").getBytes(StandardCharsets.UTF_8));
                   // The cache identity also includes the geometry itself, so a CRC collision cannot reuse an old map.
            for (String state : rel.palette) {
                digest.update(state.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            for (short cell : rel.blockIndices) {
                digest.update((byte) cell);
                digest.update((byte) (cell >>> 8));
            }
            relative.add(rel);
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        Result hit = recent.get(hash);
        if (hit != null) return hit;
        var manifest = new ReplayMapManifest(raw.map, raw.dimension, bounds, hash, entries);
        var patches = new ArrayList<byte[]>(sections.size());
        long size = 0;
        for (var section : relative) {
            byte[] encoded = SceneAssetCodec.encode(ReplaySectionGeometry.asset(manifest, List.of(section)));
            if (encoded.length > ReplayPackets.MAX_SECTION_BYTES) throw new IOException("Replay section exceeds wire limit");
            size += encoded.length;
            if (size > 32 * 1024 * 1024) throw new IOException("Replay map exceeds memory limit");
            patches.add(encoded);
        }
        return new Result(manifest, List.copyOf(patches), size);
    }
}
