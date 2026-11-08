package com.habitrain.core.api.scene.asset;

import com.habitrain.core.api.scene.model.SceneBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class S5SceneDeltaCoordinatesTest {
    @ParameterizedTest
    @CsvSource({"0,0,0,0,0,0,3", "0,0,0,16,0,0,3", "0,0,0,-16,0,0,3",
            "-32,-16,-32,-16,0,-16,3", "0,0,0,16,16,16,2", "0,0,0,64,0,0,4"})
    void shrinkAndMoveReconstructTheExactPublishedTarget(int bx, int by, int bz, int tx, int ty, int tz, int size) throws Exception {
        var base = asset(new SceneBounds(bx, by, bz, bx + 64, by + 64, bz + 64), false);
        var target = asset(new SceneBounds(tx, ty, tz, tx + size * 16, ty + size * 16, tz + size * 16), true);
        byte[] expected = SceneAssetCodec.encode(target);
        var descriptor = new SceneAssetDescriptor(SceneAssetCodec.calculateSha256(expected),
                SceneAssetCodec.estimateUncompressedSize(target), expected.length, target.sections.size(), 3955, "fixture", 1L);
        var delta = SceneAssetDelta.build(base, SceneAssetCodec.calculateSha256(SceneAssetCodec.encode(base)), target, descriptor.sha256());
        assertNotNull(delta);
        var decoded = SceneAssetDelta.decode(SceneAssetDelta.encode(delta));
        assertArrayEquals(expected, SceneAssetDelta.encodeVerifiedTarget(SceneAssetDelta.apply(base, decoded), descriptor));
    }

    @Test void boundsOnlyChangeWithNoTouchedSectionsStillRoundTrips() throws Exception {
        var base = asset(new SceneBounds(0, 0, 0, 16, 16, 16), false);
        var target = new SceneAssetCodec.AssetData(3, 3955, "minecraft:overworld",
                new SceneBounds(1, 0, 0, 16, 16, 16), new SceneBounds(1, 0, 0, 16, 16, 16),
                "fixture", base.sections, List.of());
        var patch = SceneAssetDelta.build(base, "a".repeat(64), target, "b".repeat(64));
        assertNotNull(patch);
        assertEquals(0, patch.touchedSectionCount());
        assertTrue(SceneAssetDelta.sameModel(target, SceneAssetDelta.apply(base, SceneAssetDelta.decode(SceneAssetDelta.encode(patch)))));
    }

    @Test void removedAndChangedCoordinatesCanCoincideAcrossDifferentOrigins() throws Exception {
        var base = asset(new SceneBounds(0, 0, 0, 32, 16, 16), false);
        var target = asset(new SceneBounds(16, 0, 0, 32, 16, 16), true);
        var patch = SceneAssetDelta.build(base, "a".repeat(64), target, "b".repeat(64));
        assertNotNull(patch);
        assertTrue(patch.removedKeys().contains(new SceneAssetDelta.SectionKey(0, 0, 0)));
        assertEquals(0, patch.changedSections().getFirst().relX);
        assertTrue(SceneAssetDelta.sameModel(target, SceneAssetDelta.apply(base, SceneAssetDelta.decode(SceneAssetDelta.encode(patch)))));
    }

    static SceneAssetCodec.AssetData asset(SceneBounds bounds, boolean mutate) {
        var sections = new ArrayList<SceneAssetCodec.SectionData>();
        for (int x = bounds.minSectionX(); x <= bounds.maxSectionX(); x++)
            for (int z = bounds.minSectionZ(); z <= bounds.maxSectionZ(); z++)
                for (int y = bounds.minSectionY(); y <= bounds.maxSectionY(); y++) {
                    short[] indices = new short[4096];
                    indices[0] = 1;
                    indices[1] = (short) (mutate && x == bounds.minSectionX() ? 0 : 1);
                    String id = "test:block_" + (x + 1000) + "_" + (y + 1000) + "_" + (z + 1000);
                    sections.add(new SceneAssetCodec.SectionData(x - bounds.minSectionX(), y - bounds.minSectionY(), z - bounds.minSectionZ(),
                            List.of("minecraft:air", id), indices, new byte[2048], new byte[2048]));
                }
        return new SceneAssetCodec.AssetData(3, 3955, "minecraft:overworld", bounds, bounds, "fixture", sections, List.of());
    }
}
