package com.habitrain.core.scene.client;

import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.*;

/** Keeps early snapshots for matching, and copies still-missing palettes before a client chunk goes away. */
final class ClientMatchMapSectionCache {
    private Scan active, previous;

    void begin(ClientLevel level) { begin(level, true); }

    void observe(ClientLevel level) { begin(level, false); }

    private void begin(ClientLevel level, boolean explicitStart) {
        var areas = AreasWorldComponent.KEY.get(level);
        if (areas.mapName == null || areas.mapName.isBlank()) return;
        SceneBounds clip = ReplaySectionGeometry.playBounds(areas.getPlayArea(), level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (!ReplaySectionGeometry.supported(clip)) return;
        if (active != null && active.level == level && active.map.equals(areas.mapName) && active.clip.equals(clip)
                && (!active.finished || !explicitStart)) return;
        if (active != null) {
            active.level = null;
            previous = active;
        }
        active = new Scan(level, areas.mapName, clip);
    }

    void finish(ClientLevel level) {
        if (active != null && active.level == level && !active.finished) {
            // The remaining already-loaded sections are still eligible until unload. Do not
            // touch newly loaded chunks after the map has been reset.
            active.finished = true;
            active.finishPass = active.pending.size();
        }
    }

    void unload(ClientLevel level, LevelChunk chunk) {
        if (active != null && active.level == level) active.unload(chunk);
    }

    void tick(ClientLevel level) {
        long deadline = System.nanoTime() + 500_000;
        if (active != null) {
            if (active.level != level) active.level = null;
            active.step(level, deadline);
        }
        if (previous != null) previous.step(null, deadline);
    }

    boolean finalizingGeometry() {
        return active != null && (!active.held.isEmpty() || active.finished && active.finishPass > 0 && !active.pending.isEmpty())
                || previous != null && !previous.held.isEmpty();
    }

    void clear() { active = null; previous = null; }

    ReplaySectionGeometry.Snapshot lookup(ReplayMapManifest manifest, ReplayMapManifest.Entry entry) {
        var p = entry.section();
        var b = manifest.sourceBounds();
        long key = SectionPos.asLong(p.x() + b.minSectionX(), p.y() + b.minSectionY(), p.z() + b.minSectionZ());
        ReplaySectionGeometry.Snapshot first = hit(active, manifest, key, entry.crc32());
        return first != null ? first : hit(previous, manifest, key, entry.crc32());
    }

    private static ReplaySectionGeometry.Snapshot hit(Scan scan, ReplayMapManifest manifest, long key, int crc) {
        if (scan == null || !scan.dimension.equals(manifest.dimension()) || !scan.map.equals(manifest.mapKey())) return null;
        var snapshot = scan.sections.get(key);
        return snapshot != null && snapshot.crc() == crc ? snapshot : null;
    }

    private static final class Scan {
        ClientLevel level;
        final String dimension, map;
        final SceneBounds clip;
        final ArrayDeque<Long> pending = new ArrayDeque<>();
        final Map<Long, ReplaySectionGeometry.Snapshot> sections = new HashMap<>();
        final Set<Long> visited = new HashSet<>();
        final LinkedHashMap<Long, PalettedContainer<BlockState>> held = new LinkedHashMap<>();
        boolean finished;
        int finishPass;

        Scan(ClientLevel level, String map, SceneBounds clip) {
            this.level = level;
            this.dimension = level.dimension().location().toString();
            this.map = map;
            this.clip = clip;
            for (int x = clip.minSectionX(); x <= clip.maxSectionX(); x++) {
                for (int z = clip.minSectionZ(); z <= clip.maxSectionZ(); z++) {
                    for (int y = clip.minSectionY(); y <= clip.maxSectionY(); y++) pending.add(SectionPos.asLong(x, y, z));
                }
            }
        }

        void unload(LevelChunk chunk) {
            int x = chunk.getPos().x, z = chunk.getPos().z;
            if (x < clip.minSectionX() || x > clip.maxSectionX() || z < clip.minSectionZ() || z > clip.maxSectionZ()) return;
            for (int y = clip.minSectionY(); y <= clip.maxSectionY(); y++) {
                long key = SectionPos.asLong(x, y, z);
                if (visited.contains(key) || held.containsKey(key)) continue;
                int i = chunk.getSectionIndexFromSectionY(y);
                if (i >= 0 && i < chunk.getSections().length && !chunk.getSections()[i].hasOnlyAir()) {
                    held.put(key, chunk.getSections()[i].getStates().copy());
                }
            }
        }

        void step(ClientLevel current, long deadline) {
            var frozen = held.entrySet().iterator();
            while (frozen.hasNext() && System.nanoTime() - deadline < 0) {
                var e = frozen.next();
                var p = SectionPos.of(e.getKey());
                put(e.getKey(), ReplaySectionGeometry.capture(p.x(), p.y(), p.z(), e.getValue(), clip));
                frozen.remove();
            }
            if (level == null || level != current || finished && finishPass <= 0) return;
            int left = pending.size();
            while (left-- > 0 && !pending.isEmpty() && System.nanoTime() - deadline < 0
                    && (!finished || finishPass-- > 0)) {
                long key = pending.removeFirst();
                if (visited.contains(key)) continue;
                var p = SectionPos.of(key);
                ChunkAccess access = level.getChunkSource().getChunk(p.x(), p.z(), ChunkStatus.FULL, false);
                if (access instanceof LevelChunk chunk) put(key, ReplaySectionGeometry.capture(chunk, p.y(), clip));
                else if (!finished) pending.addLast(key);
            }
        }

        void put(long key, ReplaySectionGeometry.Snapshot section) {
            visited.add(key);
            if (section != null) sections.put(key, section);
        }
    }
}
