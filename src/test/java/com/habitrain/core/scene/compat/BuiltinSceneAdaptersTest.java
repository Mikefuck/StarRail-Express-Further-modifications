package com.habitrain.core.scene.compat;

import com.habitrain.core.api.client.scene.compat.SceneBakeContext;
import com.habitrain.core.api.client.scene.compat.SceneBakeResult;
import com.habitrain.core.api.client.scene.compat.SceneBlockMeshAdapter;
import com.habitrain.core.api.scene.compat.SceneBlockCaptureAdapter;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import com.habitrain.core.scene.client.compat.SceneBlockMeshAdapterRegistry;
import com.habitrain.core.scene.client.compat.builtin.BuiltinClientSceneAdapters;
import com.habitrain.core.scene.compat.builtin.BuiltinSceneAdapters;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BuiltinSceneAdaptersTest {

    @Test
    public void testBuiltinCaptureAdapter() {
        BuiltinSceneAdapters.registerCommon();
        SceneBlockCaptureAdapter adapter = SceneBlockCaptureAdapterRegistry.getInstance()
                .get(BuiltinSceneAdapters.BUILTIN_STATIC_MODEL_ID);

        assertNotNull(adapter);
        assertEquals(BuiltinSceneAdapters.BUILTIN_STATIC_MODEL_ID, adapter.adapterId());
        assertEquals(1, adapter.dataVersion());

        assertFalse(adapter.supports(null, null));

        SceneRenderPayload payload = adapter.captureVisualData(null);
        assertNotNull(payload);
        assertNotNull(payload.customData());
        assertEquals("builtin_static_model", payload.customData().getString("adapter"));
    }

    @Test
    public void testBuiltinMeshAdapter() {
        BuiltinClientSceneAdapters.registerClient();
        SceneBlockMeshAdapter adapter = SceneBlockMeshAdapterRegistry.getInstance()
                .get(BuiltinSceneAdapters.BUILTIN_STATIC_MODEL_ID);

        assertNotNull(adapter);
        assertEquals(BuiltinSceneAdapters.BUILTIN_STATIC_MODEL_ID, adapter.adapterId());

        CompoundTag tag = new CompoundTag();
        tag.putString("adapter", "builtin_static_model");
        SceneRenderPayload payload = new SceneRenderPayload(java.util.List.of(), tag);

        assertFalse(adapter.supports(null, payload));
    }
}
