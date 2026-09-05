package com.habitrain.core.scene;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneBounds;
import com.habitrain.core.scene.network.SceneEditorOpenS2C;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SceneEditorOpenS2CTest {
    @Test
    void codecKeepsEditorRuntimeAndSelectionMapKeysSeparate() {
        SceneBounds bounds = new SceneBounds(1, 2, 3, 4, 5, 6);
        SceneAssetDescriptor descriptor = new SceneAssetDescriptor(
                "abc", 100L, 50L, 3, 1, "fingerprint", 123L);
        SceneEditorOpenS2C original = new SceneEditorOpenS2C(
                "map_editor", "map_runtime", "map_selection",
                7, "{\"enabled\":true}", bounds, descriptor);
        ByteBuf buffer = Unpooled.buffer();

        SceneEditorOpenS2C.CODEC.encode(buffer, original);
        SceneEditorOpenS2C decoded = SceneEditorOpenS2C.CODEC.decode(buffer);

        assertEquals("map_editor", decoded.editorMapKey());
        assertEquals(com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID, decoded.editorBackgroundId());
        assertEquals("map_runtime", decoded.runtimeMapKey());
        assertEquals("map_selection", decoded.selectionMapKey());
        assertEquals(7, decoded.profileRevision());
        assertEquals(bounds, decoded.sessionSelection());
        assertEquals(descriptor, decoded.assetDescriptor());
    }

    @Test
    void v3CodecPreservesCustomEditorBackgroundId() {
        SceneBounds bounds = new SceneBounds(1, 2, 3, 4, 5, 6);
        SceneAssetDescriptor descriptor = new SceneAssetDescriptor(
                "abc", 100L, 50L, 3, 1, "fingerprint", 123L);
        SceneEditorOpenS2C original = new SceneEditorOpenS2C(
                "map_editor", "custom_background_1", "map_runtime", "map_selection",
                7, "{\"enabled\":true}", bounds, descriptor);
        ByteBuf buffer = Unpooled.buffer();

        SceneEditorOpenS2C.CODEC.encode(buffer, original);
        SceneEditorOpenS2C decoded = SceneEditorOpenS2C.CODEC.decode(buffer);

        assertEquals("map_editor", decoded.editorMapKey());
        assertEquals("custom_background_1", decoded.editorBackgroundId());
        assertEquals("map_runtime", decoded.runtimeMapKey());
        assertEquals("map_selection", decoded.selectionMapKey());
        assertEquals(7, decoded.profileRevision());
        assertEquals(bounds, decoded.sessionSelection());
        assertEquals(descriptor, decoded.assetDescriptor());
    }

    @Test
    void v2PayloadFallsBackToDefaultBackgroundId() {
        ByteBuf v2Buffer = Unpooled.buffer();
        v2Buffer.writeInt(0x48534345); // PROTOCOL_MAGIC (HSCE)
        v2Buffer.writeInt(2); // PROTOCOL_VERSION 2
        writeString(v2Buffer, "map_v2");
        writeString(v2Buffer, "runtime_v2");
        writeString(v2Buffer, "selection_v2");
        v2Buffer.writeInt(3);
        writeString(v2Buffer, "{\"enabled\":false}");
        v2Buffer.writeInt(0); // minX
        v2Buffer.writeInt(0); // minY
        v2Buffer.writeInt(0); // minZ
        v2Buffer.writeInt(0); // maxX
        v2Buffer.writeInt(0); // maxY
        v2Buffer.writeInt(0); // maxZ

        writeString(v2Buffer, ""); // hash
        v2Buffer.writeLong(0L); // uncompressedSize
        v2Buffer.writeLong(0L); // compressedSize
        v2Buffer.writeInt(0); // sectionCount
        v2Buffer.writeInt(0); // dataVersion
        writeString(v2Buffer, ""); // fingerprint
        v2Buffer.writeLong(0L); // createdAt

        SceneEditorOpenS2C decoded = SceneEditorOpenS2C.CODEC.decode(v2Buffer);

        assertEquals("map_v2", decoded.editorMapKey());
        assertEquals(com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID, decoded.editorBackgroundId());
        assertEquals("runtime_v2", decoded.runtimeMapKey());
        assertEquals("selection_v2", decoded.selectionMapKey());
        assertEquals(3, decoded.profileRevision());
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    @Test
    void legacyPayloadIsRejectedInsteadOfBeingSilentlyMisparsed() {
        ByteBuf legacy = Unpooled.buffer();
        byte[] oldMapKey = "map1".getBytes(StandardCharsets.UTF_8);
        legacy.writeInt(oldMapKey.length);
        legacy.writeBytes(oldMapKey);

        assertThrows(DecoderException.class, () -> SceneEditorOpenS2C.CODEC.decode(legacy));
    }
}
