package com.habitrain.core.api.scene.asset;

import com.habitrain.core.api.scene.model.SceneBounds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class S5SceneCodecEstimateTest {
    @ParameterizedTest @ValueSource(ints = {2, 3})
    void estimateBoundsTheActualModifiedUtfEncodingIncludingNulAndSurrogates(int format) throws Exception {
        short[] indices = new short[4096]; Arrays.fill(indices, (short) 1);
        var section = new SceneAssetCodec.SectionData(0, 0, 0,
                List.of("minecraft:air", "test:" + "\0".repeat(600) + "😀".repeat(50)), indices, new byte[2048], new byte[2048]);
        var asset = new SceneAssetCodec.AssetData(3, 3955, "minecraft:overworld",
                new SceneBounds(0, 0, 0, 16, 16, 16), new SceneBounds(0, 0, 0, 16, 16, 16), "fixture", List.of(section), List.of());
        byte[] encoded = SceneAssetCodec.encode(asset, format);
        try (var stream = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            assertTrue(SceneAssetCodec.estimateUncompressedSize(asset, format) >= stream.readAllBytes().length);
        }
        assertEquals(section.palette, SceneAssetCodec.decode(encoded).sections.getFirst().palette);
    }
}
