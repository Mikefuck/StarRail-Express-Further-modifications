package com.habitrain.core.scene.client;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneRotation;
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

    @Test
    void fixedRotationIsAppliedAroundTheConfiguredLocalPivot() {
        Matrix4f model = SceneRenderRuntime.buildSceneModelMatrix(
                10.0, 0.0, 20.0,
                new double[]{1.0, 0.0, 1.0},
                new SceneRotation(90.0, 0.0, 0.0),
                null, 0.0, false);

        Vector3f pivot = model.transformPosition(new Vector3f(1.0f, 0.0f, 1.0f));
        assertEquals(11.0f, pivot.x, 0.0001f);
        assertEquals(0.0f, pivot.y, 0.0001f);
        assertEquals(21.0f, pivot.z, 0.0001f);

        Vector3f rotated = model.transformPosition(new Vector3f(2.0f, 0.0f, 1.0f));
        assertEquals(11.0f, rotated.x, 0.0001f);
        assertEquals(0.0f, rotated.y, 0.0001f);
        assertEquals(20.0f, rotated.z, 0.0001f);
    }
}
