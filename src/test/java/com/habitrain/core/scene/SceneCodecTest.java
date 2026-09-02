package com.habitrain.core.scene;

import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.model.SceneBounds;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SceneCodecTest {

    @Test
    public void testEncodeDecodeRoundtrip() throws IOException {
        // Section 0
        List<String> p0 = new ArrayList<>(List.of("minecraft:air", "minecraft:stone", "minecraft:glass"));
        short[] b0 = new short[4096];
        b0[0] = 1;
        b0[100] = 2;
        byte[] sky0 = new byte[2048];
        sky0[0] = (byte) 0xFF;
        byte[] blk0 = new byte[2048];
        blk0[0] = (byte) 0x77;
        SceneAssetCodec.SectionData sec0 = new SceneAssetCodec.SectionData(0, 0, 0, p0, b0, sky0, blk0);

        // Section 1
        List<String> p1 = new ArrayList<>(List.of("minecraft:air", "minecraft:oak_planks"));
        short[] b1 = new short[4096];
        b1[50] = 1;
        byte[] sky1 = new byte[2048];
        byte[] blk1 = new byte[2048];
        SceneAssetCodec.SectionData sec1 = new SceneAssetCodec.SectionData(1, 0, 0, p1, b1, sky1, blk1);

        SceneBounds bounds = new SceneBounds(0, 0, 0, 32, 16, 16);
        SceneAssetCodec.AssetData original = new SceneAssetCodec.AssetData(
                1, "minecraft:overworld", bounds, "test_fingerprint", List.of(sec0, sec1)
        );

        // Encode to compressed bytes
        byte[] encodedBytes = SceneAssetCodec.encode(original);
        assertNotNull(encodedBytes);
        assertTrue(encodedBytes.length > 0);

        // Checksum calculation
        String sha256 = SceneAssetCodec.calculateSha256(encodedBytes);
        assertNotNull(sha256);
        assertEquals(64, sha256.length());

        long crc32 = SceneAssetCodec.calculateCrc32(encodedBytes, 0, encodedBytes.length);
        assertTrue(crc32 != 0);

        // Decode back
        SceneAssetCodec.AssetData decoded = SceneAssetCodec.decode(encodedBytes);
        assertNotNull(decoded);
        assertEquals(original.formatVersion, decoded.formatVersion);
        assertEquals(original.dataVersion, decoded.dataVersion);
        assertEquals(original.dimensionId, decoded.dimensionId);
        assertEquals(original.sourceBounds.sizeX(), decoded.sourceBounds.sizeX());
        assertEquals(2, decoded.sections.size());

        SceneAssetCodec.SectionData decSec0 = decoded.sections.get(0);
        assertEquals(0, decSec0.relX);
        assertEquals(0, decSec0.relY);
        assertEquals(0, decSec0.relZ);
        assertEquals(3, decSec0.palette.size());
        assertEquals("minecraft:air", decSec0.palette.get(0));
        assertEquals("minecraft:stone", decSec0.palette.get(1));
        assertEquals("minecraft:glass", decSec0.palette.get(2));
        assertEquals(1, decSec0.blockIndices[0]);
        assertEquals(2, decSec0.blockIndices[100]);
        assertEquals((byte) 0xFF, decSec0.skyLight[0]);
        assertEquals((byte) 0x77, decSec0.blockLight[0]);

        SceneAssetCodec.SectionData decSec1 = decoded.sections.get(1);
        assertEquals(1, decSec1.relX);
        assertEquals(0, decSec1.relY);
        assertEquals(0, decSec1.relZ);
        assertEquals(2, decSec1.palette.size());
        assertEquals("minecraft:air", decSec1.palette.get(0));
        assertEquals("minecraft:oak_planks", decSec1.palette.get(1));
        assertEquals(1, decSec1.blockIndices[50]);
    }

    @Test
    public void rejectsPaletteIndexOutsideSectionPalette() throws IOException {
        short[] indices = new short[4096];
        indices[12] = 2;
        SceneAssetCodec.SectionData invalid = new SceneAssetCodec.SectionData(
                0, 0, 0, List.of("minecraft:air", "minecraft:stone"),
                indices, new byte[2048], new byte[2048]);
        byte[] encoded = SceneAssetCodec.encode(new SceneAssetCodec.AssetData(
                1, "minecraft:overworld", new SceneBounds(0, 0, 0, 16, 16, 16),
                "test", List.of(invalid)));
        assertThrows(IOException.class, () -> SceneAssetCodec.decode(encoded));
    }
}
