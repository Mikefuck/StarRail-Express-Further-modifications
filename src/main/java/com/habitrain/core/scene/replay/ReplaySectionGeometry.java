package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.SceneLimits;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.server.SceneCaptureService;
import net.minecraft.SharedConstants;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.zip.CRC32;

/** Side-neutral snapshot used for both server patches and local cache matching. */
public final class ReplaySectionGeometry {
    private ReplaySectionGeometry() {}

    public static SceneBounds playBounds(@Nullable AABB area, int bottom, int top) {
        if (area == null || area.getXsize() <= 0 || area.getYsize() <= 0 || area.getZsize() <= 0) return SceneBounds.EMPTY;
        int y = Math.max(bottom, Mth.floor(area.minY)), yy = Math.min(top, Mth.ceil(area.maxY));
        if (y >= yy) return SceneBounds.EMPTY;
        return new SceneBounds(Mth.floor(area.minX), y, Mth.floor(area.minZ), Mth.ceil(area.maxX), yy, Mth.ceil(area.maxZ));
    }

    public static boolean supported(SceneBounds bounds) {
        return !bounds.isEmpty() && bounds.totalSections() <= ReplayMapManifest.MAX_SECTIONS
                && bounds.sizeX() <= SceneLimits.MAX_AXIS_LENGTH && bounds.sizeY() <= SceneLimits.MAX_AXIS_LENGTH
                && bounds.sizeZ() <= SceneLimits.MAX_AXIS_LENGTH;
    }

    public record Snapshot(SceneAssetCodec.SectionData data, int crc, SceneBounds occupied) {}

    /** World section coordinates go in; its cell order is y/z/x, clipped to the exact play bounds. */
    public static @Nullable Snapshot capture(LevelChunk chunk, int sy, SceneBounds clip) {
        int index = chunk.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= chunk.getSections().length) return null;
        LevelChunkSection section = chunk.getSections()[index];
        if (section.hasOnlyAir()) return null;
        return capture(chunk.getPos().x, sy, chunk.getPos().z, section.getStates(), clip);
    }

    public static @Nullable Snapshot capture(int sx, int sy, int sz, PalettedContainer<BlockState> states, SceneBounds clip) {
        int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
        int xMin = 16, yMin = 16, zMin = 16, xMax = 0, yMax = 0, zMax = 0;
        var ids = new IdentityHashMap<BlockState, Short>();
        var palette = new ArrayList<String>();
        palette.add(SceneAssetCodec.AIR_BLOCK_ID);
        short[] cells = new short[4096];
        for (int y = Math.max(0, clip.minY() - y0); y < Math.min(16, clip.maxY() - y0); y++) {
            for (int z = Math.max(0, clip.minZ() - z0); z < Math.min(16, clip.maxZ() - z0); z++) {
                for (int x = Math.max(0, clip.minX() - x0); x < Math.min(16, clip.maxX() - x0); x++) {
                    BlockState state = states.get(x, y, z);
                    if (state.isAir()) continue;
                    Short id = ids.get(state);
                    if (id == null) {
                        id = (short) palette.size();
                        ids.put(state, id);
                        palette.add(SceneCaptureService.serializeBlockState(state));
                    }
                    cells[(y << 8) | (z << 4) | x] = id;
                    xMin = Math.min(xMin, x); yMin = Math.min(yMin, y); zMin = Math.min(zMin, z);
                    xMax = Math.max(xMax, x + 1); yMax = Math.max(yMax, y + 1); zMax = Math.max(zMax, z + 1);
                }
            }
        }
        if (ids.isEmpty()) return null;
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xff);
        var data = new SceneAssetCodec.SectionData(sx, sy, sz, List.copyOf(palette), cells, sky, null);
        return new Snapshot(data, crc(data), new SceneBounds(x0 + xMin, y0 + yMin, z0 + zMin, x0 + xMax, y0 + yMax, z0 + zMax));
    }

    public static int crc(SceneAssetCodec.SectionData data) {
        CRC32 crc = new CRC32();
        for (String state : data.palette) {
            crc.update(state.getBytes(StandardCharsets.UTF_8));
            crc.update(0);
        }
        for (short cell : data.blockIndices) {
            crc.update(cell & 255);
            crc.update((cell >>> 8) & 255);
        }
        return (int) crc.getValue();
    }

    public static SceneAssetCodec.SectionData relative(SceneAssetCodec.SectionData absolute, SceneBounds bounds) {
        return new SceneAssetCodec.SectionData(absolute.relX - bounds.minSectionX(),
                absolute.relY - bounds.minSectionY(), absolute.relZ - bounds.minSectionZ(),
                absolute.palette, absolute.blockIndices, absolute.skyLight, absolute.blockLight);
    }

    public static SceneAssetCodec.AssetData asset(ReplayMapManifest manifest, List<SceneAssetCodec.SectionData> parts) {
        return new SceneAssetCodec.AssetData(SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
                manifest.dimension(), manifest.sourceBounds(), manifest.hash(), List.copyOf(parts));
    }
}
