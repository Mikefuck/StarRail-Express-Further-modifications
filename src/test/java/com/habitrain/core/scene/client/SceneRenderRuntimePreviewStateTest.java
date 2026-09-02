package com.habitrain.core.scene.client;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneRenderRuntimePreviewStateTest {
    private final SceneRenderRuntime runtime = SceneRenderRuntime.getInstance();

    @AfterEach
    void resetRuntime() {
        runtime.reset();
    }

    @Test
    void previewStateIsOwnedByRuntimeAndScopedToItsMap() {
        runtime.startPreview("wathe", new SceneProfile(), SceneAssetDescriptor.EMPTY);

        assertTrue(runtime.isPreviewActive());
        assertTrue(runtime.isPreviewActiveFor("wathe"));
        assertFalse(runtime.isPreviewActiveFor("another_map"));

        runtime.updatePreviewProfile("wathe", new SceneProfile());
        assertTrue(runtime.isPreviewActiveFor("wathe"));

        runtime.stopPreview();
        assertFalse(runtime.isPreviewActive());
        assertFalse(runtime.isPreviewActiveFor("wathe"));
    }

    @Test
    void sceneModelTransformIsComposedWithTheCameraViewMatrix() {
        Matrix4f view = new Matrix4f().rotationY(0.75f);
        Matrix4f model = new Matrix4f().translation(2.0f, 3.0f, 0.0f);

        Vector3f expected = view.transformPosition(new Vector3f(2.0f, 3.0f, 0.0f));
        Vector3f actual = SceneRenderRuntime.composeModelView(view, model)
                .transformPosition(new Vector3f());

        assertEquals(expected.x, actual.x, 0.0001f);
        assertEquals(expected.y, actual.y, 0.0001f);
        assertEquals(expected.z, actual.z, 0.0001f);
    }
}
