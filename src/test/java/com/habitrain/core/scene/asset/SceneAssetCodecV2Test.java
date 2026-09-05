package com.habitrain.core.scene.asset;

import com.habitrain.core.api.scene.compat.SceneBlockPayloadEntry;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.model.SceneBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class SceneAssetCodecV2Test {

    @Test
    public void testV1BackwardCompatibility() throws IOException {
        // Construct a raw v1 stream
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
             DataOutputStream dos = new DataOutputStream(gzos)) {
            dos.writeInt(SceneAssetCodec.MAGIC);
            dos.writeInt(SceneAssetCodec.FORMAT_VERSION_V1);
            dos.writeInt(3955);
            dos.writeUTF("minecraft:overworld");
            // sourceBounds
            dos.writeInt(0);
            dos.writeInt(64);
            dos.writeInt(0);
            dos.writeInt(16);
            dos.writeInt(80);
            dos.writeInt(16);
            dos.writeUTF("test_v1_fingerprint");
            dos.writeInt(1); // 1 section

            CRC32 crc = new CRC32();
            dos.writeInt(0); // rx
            dos.writeInt(0); // ry
            dos.writeInt(0); // rz
            dos.writeInt(2); // palette size
            dos.writeUTF("minecraft:air");
            dos.writeUTF("minecraft:stone");
            for (int i = 0; i < 4096; i++) {
                dos.writeShort(i == 0 ? 1 : 0);
            }
            byte[] sky = new byte[2048];
            sky[0] = (byte) 0xFF;
            dos.write(sky);
            crc.update(sky);

            byte[] block = new byte[2048];
            block[0] = (byte) 0x11;
            dos.write(block);
            crc.update(block);

            dos.writeLong(crc.getValue());
            dos.flush();
        }

        byte[] v1Bytes = baos.toByteArray();
        SceneAssetCodec.AssetData decoded = SceneAssetCodec.decode(v1Bytes);

        assertNotNull(decoded);
        assertEquals(1, decoded.formatVersion);
        assertEquals(3955, decoded.dataVersion);
        assertEquals("minecraft:overworld", decoded.dimensionId);
        assertEquals(new SceneBounds(0, 64, 0, 16, 80, 16), decoded.sourceBounds);
        assertEquals(decoded.sourceBounds, decoded.haloBounds);
        assertEquals("test_v1_fingerprint", decoded.fingerprint);
        assertEquals(1, decoded.sections.size());
        assertTrue(decoded.blockPayloads.isEmpty());
    }

    @Test
    public void testV2RoundTripWithHaloAndPayloads() throws IOException {
        SceneBounds source = new SceneBounds(10, 60, 20, 26, 76, 36);
        SceneBounds halo = source.inflate(1);

        List<String> palette = List.of("minecraft:air", "minecraft:iron_block");
        short[] indices = new short[4096];
        indices[5] = 1;
        byte[] sky = new byte[2048];
        byte[] blk = new byte[2048];
        SceneAssetCodec.SectionData sec = new SceneAssetCodec.SectionData(0, 0, 0, palette, indices, sky, blk);

        CompoundTag customData = new CompoundTag();
        customData.putString("render_mode", "complex_multipart");
        customData.putInt("tint_color", 0xFF00FF);

        SceneRenderPayload.VisualVertex v0 = SceneRenderPayload.VisualVertex.of(0, 0, 0, 0, 0, 0xFFFFFFFF);
        SceneRenderPayload.VisualVertex v1 = SceneRenderPayload.VisualVertex.of(1, 0, 0, 1, 0, 0xFFFFFFFF);
        SceneRenderPayload.VisualVertex v2 = SceneRenderPayload.VisualVertex.of(1, 1, 0, 1, 1, 0xFFFFFFFF);
        SceneRenderPayload.VisualVertex v3 = SceneRenderPayload.VisualVertex.of(0, 1, 0, 0, 1, 0xFFFFFFFF);

        SceneRenderPayload payload = SceneRenderPayload.builder()
                .addQuad(ResourceLocation.parse("minecraft:block/stone"), v0, v1, v2, v3)
                .customData(customData)
                .build();

        SceneBlockPayloadEntry entry = new SceneBlockPayloadEntry(
                2, 3, 4, ResourceLocation.parse("examplemod:polygon_adapter"), 1, payload);

        SceneAssetCodec.AssetData original = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V2,
                3955,
                "minecraft:the_nether",
                source,
                halo,
                "v2_fingerprint",
                List.of(sec),
                List.of(entry)
        );

        byte[] encoded = SceneAssetCodec.encode(original);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        SceneAssetCodec.AssetData decoded = SceneAssetCodec.decode(encoded);
        assertEquals(2, decoded.formatVersion);
        assertEquals(3955, decoded.dataVersion);
        assertEquals("minecraft:the_nether", decoded.dimensionId);
        assertEquals(source, decoded.sourceBounds);
        assertEquals(halo, decoded.haloBounds);
        assertEquals("v2_fingerprint", decoded.fingerprint);
        assertEquals(1, decoded.sections.size());
        assertEquals(1, decoded.blockPayloads.size());

        SceneBlockPayloadEntry decEntry = decoded.blockPayloads.get(0);
        assertEquals(2, decEntry.localX());
        assertEquals(3, decEntry.localY());
        assertEquals(4, decEntry.localZ());
        assertEquals(ResourceLocation.parse("examplemod:polygon_adapter"), decEntry.adapterId());
        assertEquals(1, decEntry.adapterVersion());

        SceneRenderPayload decPayload = decEntry.payload();
        assertEquals(1, decPayload.meshes().size());
        SceneRenderPayload.VisualMesh mesh = decPayload.meshes().get(0);
        assertEquals(ResourceLocation.parse("minecraft:block/stone"), mesh.texture());
        assertEquals(SceneRenderPayload.PrimitiveType.QUADS, mesh.primitive());
        assertEquals(4, mesh.vertices().size());

        assertNotNull(decPayload.customData());
        assertEquals("complex_multipart", decPayload.customData().getString("render_mode"));
        assertEquals(0xFF00FF, decPayload.customData().getInt("tint_color"));
    }

    @Test
    public void testRejectsForbiddenSecurityKeysInPayloadNbt() {
        List<String> forbidden = List.of("Items", "Inventory", "LootTable", "Command", "Owner", "CustomName", "Lock", "Recipes");
        for (String key : forbidden) {
            CompoundTag badTag = new CompoundTag();
            badTag.putString(key, "malicious_content");
            assertThrows(IOException.class, () -> SceneRenderPayload.builder().customData(badTag).build(),
                    "Expected forbidden key '" + key + "' to be rejected");
        }
    }

    @Test
    public void testRejectsExcessiveNbtDepth() {
        CompoundTag root = new CompoundTag();
        CompoundTag cur = root;
        // Depth 0 (root) -> 1 -> 2 -> 3 -> 4 (exceeds limit 3)
        for (int i = 0; i < 4; i++) {
            CompoundTag child = new CompoundTag();
            cur.put("level" + i, child);
            cur = child;
        }
        cur.putString("leaf", "deep");

        CompoundTag testTag = root;
        assertThrows(IOException.class, () -> SceneRenderPayload.builder().customData(testTag).build());
    }

    @Test
    public void testRejectsForbiddenKeysNestedInsideLists() {
        CompoundTag root = new CompoundTag();
        CompoundTag nested = new CompoundTag();
        nested.putString("Items", "secret_inventory");
        ListTag list = new ListTag();
        list.add(nested);
        root.put("visual_layers", list);

        assertThrows(IOException.class, () -> SceneRenderPayload.builder().customData(root).build());
    }

    @Test
    public void testRejectsNonFiniteAndMisalignedVertices() {
        SceneRenderPayload.VisualVertex finite = SceneRenderPayload.VisualVertex.of(
                0, 0, 0, 0, 0, 0xFFFFFFFF);
        SceneRenderPayload.VisualVertex nan = SceneRenderPayload.VisualVertex.of(
                Float.NaN, 0, 0, 0, 0, 0xFFFFFFFF);

        assertThrows(IOException.class, () -> SceneRenderPayload.builder()
                .addMesh(new SceneRenderPayload.VisualMesh(
                        ResourceLocation.parse("minecraft:block/stone"),
                        SceneRenderPayload.PrimitiveType.QUADS,
                        List.of(nan, finite, finite, finite)))
                .build());
        assertThrows(IOException.class, () -> SceneRenderPayload.builder()
                .addMesh(new SceneRenderPayload.VisualMesh(
                        ResourceLocation.parse("minecraft:block/stone"),
                        SceneRenderPayload.PrimitiveType.QUADS,
                        List.of(finite, finite, finite)))
                .build());
    }

    @Test
    public void testRejectsUnknownPrimitiveAndTrailingPayloadData() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(1);
            out.writeUTF("minecraft:block/stone");
            out.writeByte(99);
        }
        assertThrows(IOException.class, () -> SceneRenderPayload.fromByteArray(raw.toByteArray()));

        SceneRenderPayload valid = SceneRenderPayload.builder()
                .addQuad(ResourceLocation.parse("minecraft:block/stone"),
                        SceneRenderPayload.VisualVertex.of(0, 0, 0, 0, 0, 0),
                        SceneRenderPayload.VisualVertex.of(1, 0, 0, 1, 0, 0),
                        SceneRenderPayload.VisualVertex.of(1, 1, 0, 1, 1, 0),
                        SceneRenderPayload.VisualVertex.of(0, 1, 0, 0, 1, 0))
                .build();
        byte[] encoded = valid.toByteArray();
        byte[] withTrailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> SceneRenderPayload.fromByteArray(withTrailing));
    }

    @Test
    public void testRejectsPayloadCoordinatesOutsideSourceBounds() throws Exception {
        SceneBounds source = new SceneBounds(0, 0, 0, 16, 16, 16);
        SceneRenderPayload payload = SceneRenderPayload.builder()
                .addQuad(ResourceLocation.parse("minecraft:block/stone"),
                        SceneRenderPayload.VisualVertex.of(0, 0, 0, 0, 0, 0),
                        SceneRenderPayload.VisualVertex.of(1, 0, 0, 1, 0, 0),
                        SceneRenderPayload.VisualVertex.of(1, 1, 0, 1, 1, 0),
                        SceneRenderPayload.VisualVertex.of(0, 1, 0, 0, 1, 0))
                .build();
        SceneBlockPayloadEntry outside = new SceneBlockPayloadEntry(
                16, 0, 0, ResourceLocation.parse("test:adapter"), 1, payload);
        SceneAssetCodec.AssetData asset = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V2, 3955, "minecraft:overworld",
                source, source, "test",
                List.of(new SceneAssetCodec.SectionData(0, 0, 0,
                        List.of("minecraft:air"), new short[4096], new byte[2048], new byte[2048])),
                List.of(outside));

        assertThrows(IOException.class, () -> SceneAssetCodec.decode(SceneAssetCodec.encode(asset)));
    }

    @Test
    public void testRejectsTooManyVerticesInSinglePayload() {
        SceneRenderPayload.Builder builder = SceneRenderPayload.builder();
        List<SceneRenderPayload.VisualVertex> vertices = new ArrayList<>();
        for (int i = 0; i < SceneLimits.MAX_VERTICES_PER_BLOCK + 1; i++) {
            vertices.add(SceneRenderPayload.VisualVertex.of(0, 0, 0, 0, 0, 0xFFFFFFFF));
        }
        builder.addMesh(new SceneRenderPayload.VisualMesh(
                ResourceLocation.parse("minecraft:block/stone"),
                SceneRenderPayload.PrimitiveType.QUADS,
                vertices
        ));
        assertThrows(IOException.class, builder::build);
    }

    @Test
    public void testRejectsTooManyMaterialsInSinglePayload() {
        SceneRenderPayload.Builder builder = SceneRenderPayload.builder();
        for (int i = 0; i < SceneLimits.MAX_MATERIALS_PER_BLOCK + 1; i++) {
            builder.addQuad(ResourceLocation.parse("minecraft:block/stone_" + i),
                    SceneRenderPayload.VisualVertex.of(0, 0, 0, 0, 0, 0),
                    SceneRenderPayload.VisualVertex.of(1, 0, 0, 1, 0, 0),
                    SceneRenderPayload.VisualVertex.of(1, 1, 0, 1, 1, 0),
                    SceneRenderPayload.VisualVertex.of(0, 1, 0, 0, 1, 0));
        }
        assertThrows(IOException.class, builder::build);
    }

    @Test
    public void testRejectsCorruptedPayloadCrc() throws IOException {
        SceneBounds source = new SceneBounds(0, 0, 0, 16, 16, 16);
        SceneRenderPayload payload = SceneRenderPayload.builder()
                .addQuad(ResourceLocation.parse("minecraft:block/dirt"),
                        SceneRenderPayload.VisualVertex.of(0, 0, 0, 0, 0, 0),
                        SceneRenderPayload.VisualVertex.of(1, 0, 0, 1, 0, 0),
                        SceneRenderPayload.VisualVertex.of(1, 1, 0, 1, 1, 0),
                        SceneRenderPayload.VisualVertex.of(0, 1, 0, 0, 1, 0))
                .build();
        SceneBlockPayloadEntry entry = new SceneBlockPayloadEntry(
                1, 1, 1, ResourceLocation.parse("test:adapter"), 1, payload);

        SceneAssetCodec.AssetData asset = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V2,
                3955, "minecraft:overworld", source, source, "test",
                List.of(new SceneAssetCodec.SectionData(0, 0, 0, List.of("minecraft:air"), new short[4096], new byte[2048], new byte[2048])),
                List.of(entry));

        byte[] encoded = SceneAssetCodec.encode(asset);
        // Corrupt a byte in the middle
        encoded[encoded.length - 20] ^= 0xFF;

        assertThrows(IOException.class, () -> SceneAssetCodec.decode(encoded));
    }
}
