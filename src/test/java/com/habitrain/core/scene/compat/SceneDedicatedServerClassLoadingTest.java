package com.habitrain.core.scene.compat;

import com.habitrain.core.api.client.scene.compat.*;
import com.habitrain.core.api.scene.compat.*;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.client.compat.SceneBlockMeshAdapterRegistry;
import com.habitrain.core.scene.model.SceneBounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SceneDedicatedServerClassLoadingTest {

    @AfterEach
    void clearAdapterRegistries() {
        SceneBlockCaptureAdapterRegistry.getInstance().clear();
        SceneBlockMeshAdapterRegistry.getInstance().clear();
    }

    private static final List<Class<?>> COMMON_CLASSES = List.of(
            SceneBlockCaptureAdapter.class,
            SceneCaptureContext.class,
            SceneRenderPayload.class,
            SceneRenderPayload.PrimitiveType.class,
            SceneRenderPayload.VisualMesh.class,
            SceneRenderPayload.VisualVertex.class,
            SceneRenderPayload.Builder.class,
            SceneBlockPayloadEntry.class,
            SceneBlockCaptureAdapters.class,
            SceneBlockCaptureAdapterRegistry.class,
            SceneAssetCodec.class,
            SceneAssetCodec.SectionData.class,
            SceneAssetCodec.AssetData.class,
            SceneLimits.class,
            SceneBounds.class,
            com.habitrain.core.scene.compat.builtin.BuiltinSceneAdapters.class,
            com.habitrain.core.scene.compat.builtin.BuiltinSceneAdapters.BuiltinStaticModelCaptureAdapter.class,
            com.habitrain.core.scene.model.ScenePublishPolicy.class,
            com.habitrain.core.scene.model.SceneMotionMode.class,
            com.habitrain.core.scene.model.SceneOrbitAxis.class,
            com.habitrain.core.scene.model.SceneOrbitCenterMode.class,
            com.habitrain.core.scene.model.SceneOrbitSettings.class,
            com.habitrain.core.scene.model.SceneOrbitMath.class,
            com.habitrain.core.scene.model.SceneInstanceTransform.class,
            com.habitrain.core.scene.model.SceneProfileValidator.class
    );

    @Test
    public void testCommonClassesDoNotReferenceClientTypes() {
        for (Class<?> clazz : COMMON_CLASSES) {
            // Check that the class itself is not annotated with @Environment(CLIENT)
            assertFalse(isClientAnnotated(clazz), clazz.getName() + " should not be client-annotated");

            // Check all declared fields
            for (Field field : clazz.getDeclaredFields()) {
                assertFalse(isClientType(field.getType()),
                        clazz.getSimpleName() + "." + field.getName() + " field type references client type: " + field.getType());
            }

            // Check all declared methods
            for (Method method : clazz.getDeclaredMethods()) {
                assertFalse(isClientType(method.getReturnType()),
                        clazz.getSimpleName() + "." + method.getName() + " return type references client type: " + method.getReturnType());
                for (Parameter param : method.getParameters()) {
                    assertFalse(isClientType(param.getType()),
                            clazz.getSimpleName() + "." + method.getName() + " parameter references client type: " + param.getType());
                }
            }
        }
    }

    @Test
    public void testCommonRegistryDoesNotReferenceClientAdapter() {
        Method[] methods = SceneBlockCaptureAdapterRegistry.class.getDeclaredMethods();
        for (Method method : methods) {
            assertNotEquals(SceneBlockMeshAdapter.class, method.getReturnType());
            for (Class<?> param : method.getParameterTypes()) {
                assertNotEquals(SceneBlockMeshAdapter.class, param);
            }
        }
    }

    @Test
    public void testCommonCaptureAdapterRegistrationAndQuery() {
        SceneBlockCaptureAdapterRegistry registry = SceneBlockCaptureAdapterRegistry.getInstance();
        registry.clear();

        ResourceLocation id = ResourceLocation.parse("test:custom_stone_adapter");
        SceneBlockCaptureAdapter adapter = new SceneBlockCaptureAdapter() {
            @Override
            public ResourceLocation adapterId() { return id; }
            @Override
            public int dataVersion() { return 1; }
            @Override
            public boolean supports(BlockState state, BlockEntity blockEntity) {
                return state == null; // Pure test predicate that does not invoke unbootstrapped vanilla Blocks
            }
            @Override
            public SceneRenderPayload captureVisualData(SceneCaptureContext context) {
                return null;
            }
        };

        SceneBlockCaptureAdapters.register(adapter);
        assertEquals(1, registry.all().size());
        assertSame(adapter, registry.get(id));
        assertSame(adapter, registry.findAdapter(null, null));

        registry.clear();
        assertTrue(registry.all().isEmpty());
    }

    @Test
    public void testCaptureRegistryRejectsDuplicateIdsAndPropagatesProbeFailures() {
        SceneBlockCaptureAdapterRegistry registry = SceneBlockCaptureAdapterRegistry.getInstance();
        registry.clear();
        ResourceLocation id = ResourceLocation.parse("test:strict_capture_adapter");
        SceneBlockCaptureAdapter throwing = new SceneBlockCaptureAdapter() {
            @Override public ResourceLocation adapterId() { return id; }
            @Override public int dataVersion() { return 1; }
            @Override public boolean supports(BlockState state, BlockEntity blockEntity) {
                throw new IllegalStateException("probe failed");
            }
            @Override public SceneRenderPayload captureVisualData(SceneCaptureContext context) { return null; }
        };
        SceneBlockCaptureAdapter duplicate = new SceneBlockCaptureAdapter() {
            @Override public ResourceLocation adapterId() { return id; }
            @Override public int dataVersion() { return 1; }
            @Override public boolean supports(BlockState state, BlockEntity blockEntity) { return false; }
            @Override public SceneRenderPayload captureVisualData(SceneCaptureContext context) { return null; }
        };

        registry.register(throwing);
        registry.register(throwing);
        assertEquals(1, registry.all().size(), "registering the same instance is idempotent");
        assertThrows(IllegalArgumentException.class, () -> registry.register(duplicate));
        assertThrows(IllegalStateException.class, () -> registry.findAdapter(null, null));
        registry.clear();
    }

    @Test
    public void testClientMeshAdapterRegistrationAndQuery() throws Exception {
        SceneBlockMeshAdapterRegistry registry = SceneBlockMeshAdapterRegistry.getInstance();
        registry.clear();

        ResourceLocation id = ResourceLocation.parse("test:client_mesh_adapter");
        SceneBlockMeshAdapter adapter = new SceneBlockMeshAdapter() {
            @Override
            public ResourceLocation adapterId() { return id; }
            @Override
            public boolean supports(BlockState state, SceneRenderPayload payload) {
                return payload != null && !payload.isEmpty();
            }
            @Override
            public SceneBakeResult emitStaticMesh(SceneBakeContext context, SceneMaterialSink materials) {
                return SceneBakeResult.SUCCESS;
            }
        };

        SceneBlockMeshAdapters.register(adapter);
        assertEquals(1, registry.all().size());
        assertSame(adapter, registry.get(id));

        SceneRenderPayload validPayload = SceneRenderPayload.builder()
                .addQuad(ResourceLocation.parse("minecraft:block/dirt"),
                        SceneRenderPayload.VisualVertex.of(0, 0, 0, 0, 0, 0),
                        SceneRenderPayload.VisualVertex.of(1, 0, 0, 1, 0, 0),
                        SceneRenderPayload.VisualVertex.of(1, 1, 0, 1, 1, 0),
                        SceneRenderPayload.VisualVertex.of(0, 1, 0, 0, 1, 0))
                .build();
        assertSame(adapter, registry.findAdapter(null, validPayload));
        assertNull(registry.findAdapter(null, new SceneRenderPayload(List.of(), null)));

        registry.clear();
        assertTrue(registry.all().isEmpty());
    }

    @Test
    public void testMeshRegistryRejectsDuplicateIdsAndPropagatesProbeFailures() {
        SceneBlockMeshAdapterRegistry registry = SceneBlockMeshAdapterRegistry.getInstance();
        registry.clear();
        ResourceLocation id = ResourceLocation.parse("test:strict_mesh_adapter");
        SceneBlockMeshAdapter throwing = new SceneBlockMeshAdapter() {
            @Override public ResourceLocation adapterId() { return id; }
            @Override public boolean supports(BlockState state, SceneRenderPayload payload) {
                throw new IllegalStateException("probe failed");
            }
            @Override public SceneBakeResult emitStaticMesh(SceneBakeContext context, SceneMaterialSink materials) {
                return SceneBakeResult.UNSUPPORTED;
            }
        };
        SceneBlockMeshAdapter duplicate = new SceneBlockMeshAdapter() {
            @Override public ResourceLocation adapterId() { return id; }
            @Override public boolean supports(BlockState state, SceneRenderPayload payload) { return false; }
            @Override public SceneBakeResult emitStaticMesh(SceneBakeContext context, SceneMaterialSink materials) {
                return SceneBakeResult.UNSUPPORTED;
            }
        };

        registry.register(throwing);
        registry.register(throwing);
        assertEquals(1, registry.all().size(), "registering the same instance is idempotent");
        assertThrows(IllegalArgumentException.class, () -> registry.register(duplicate));
        assertThrows(IllegalStateException.class, () -> registry.findAdapter(null, null));
        registry.clear();
    }

    private static boolean isClientType(Class<?> type) {
        if (type == null) return false;
        if (type.isArray()) return isClientType(type.getComponentType());
        String name = type.getName();
        if (name.startsWith("net.minecraft.client.")) return true;
        if (name.startsWith("com.mojang.blaze3d.")) return true;
        if (name.startsWith("com.habitrain.core.api.client.")) return true;
        if (name.startsWith("com.habitrain.core.client.")) return true;
        if (name.startsWith("com.habitrain.core.scene.client.")) return true;
        return isClientAnnotated(type);
    }

    private static boolean isClientAnnotated(Class<?> type) {
        for (java.lang.annotation.Annotation ann : type.getAnnotations()) {
            if (ann.annotationType().getName().contains("Environment")
                    && ann.toString().contains("CLIENT")) {
                return true;
            }
        }
        return false;
    }
}
