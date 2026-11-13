package com.habitrain.core.scene.replay;

import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.compat.SceneBlockPayloadEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Builds a cut-away copy of a replay diorama asset: every block at or above a local Y plane is
 * removed before meshing, so the mesh builder regenerates the now-exposed wall tops and floors
 * instead of leaving see-through holes (which a fragment clip plane would).
 *
 * <p>The source asset is never modified: untouched sections are shared by reference, touched
 * sections receive a fresh index array, sections entirely above the plane are dropped.</p>
 */
public final class ReplayRoofCutaway {
    private ReplayRoofCutaway() {}

    /**
     * Converts the backend's ceiling hint (relative to the asset minimum Y) into the first removed
     * local layer. The hint assumes a three-block cabin; one layer lower also opens two-block cabins
     * while keeping the floor, doors, partitions and lower hull outline.
     *
     * @return the first local Y to remove, or -1 for "do not cut"
     */
    public static int cutLayer(OptionalDouble roofHint, int sizeY) {
        if (roofHint == null || roofHint.isEmpty() || sizeY < 4) return -1;
        int cut = Math.max(2, (int) Math.floor(roofHint.getAsDouble()) - 1);
        return cut < sizeY ? cut : -1;
    }

    public static SceneAssetCodec.AssetData apply(SceneAssetCodec.AssetData asset, int cutLayer) {
        if (asset == null || cutLayer <= 0 || cutLayer >= asset.sourceBounds.sizeY()) return asset;
        int base = Math.floorDiv(asset.sourceBounds.minY(), 16) * 16 - asset.sourceBounds.minY();
        List<SceneAssetCodec.SectionData> sections = new ArrayList<>(asset.sections.size());
        for (SceneAssetCodec.SectionData section : asset.sections) {
            int originY = section.relY * 16 + base;
            if (originY >= cutLayer) continue;
            if (originY + 16 <= cutLayer) {
                sections.add(section);
                continue;
            }
            int keep = cutLayer - originY;
            short[] cells = section.blockIndices.clone();
            boolean any = false;
            for (int i = 0; i < cells.length; i++) {
                if ((i >> 8) >= keep) cells[i] = 0;
                else if (cells[i] != 0) any = true;
            }
            if (any) {
                sections.add(new SceneAssetCodec.SectionData(section.relX, section.relY, section.relZ,
                        section.palette, cells, section.skyLight, section.blockLight));
            }
        }
        List<SceneBlockPayloadEntry> payloads = new ArrayList<>();
        for (SceneBlockPayloadEntry entry : asset.blockPayloads) {
            if (entry.localY() < cutLayer) payloads.add(entry);
        }
        return new SceneAssetCodec.AssetData(asset.formatVersion, asset.dataVersion, asset.dimensionId,
                asset.sourceBounds, asset.haloBounds, asset.fingerprint + "#roof" + cutLayer, sections, payloads);
    }
}
