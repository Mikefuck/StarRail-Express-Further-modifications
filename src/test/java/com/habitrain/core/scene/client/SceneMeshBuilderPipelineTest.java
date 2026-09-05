package com.habitrain.core.scene.client;

import com.habitrain.core.api.client.scene.compat.SceneMaterialSink;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import com.habitrain.core.scene.model.SceneBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SceneMeshBuilderPipelineTest {

    @Test
    public void testLocalSectionOriginAndNibbleMath() {
        int origin0 = SceneMeshBuilder.localSectionOrigin(0, 0);
        assertEquals(0, origin0);

        int origin1 = SceneMeshBuilder.localSectionOrigin(1, 10);
        assertEquals(6, origin1);

        byte[] lightData = new byte[2048];
        lightData[0] = (byte) 0xA5; // lower nibble = 5, upper nibble = 10
        assertEquals(5, SceneMeshBuilder.readNibble(lightData, 0));
        assertEquals(10, SceneMeshBuilder.readNibble(lightData, 1));
        assertEquals(0, SceneMeshBuilder.readNibble(lightData, -1));
        assertEquals(0, SceneMeshBuilder.readNibble(lightData, 4096));
    }

    @Test
    public void testGeometryNeighborsStopAtSelectionBoundary() {
        SceneBounds source = new SceneBounds(100, 20, -30, 103, 22, -26);

        assertTrue(SceneMeshBuilder.containsLocalGeometry(source, new BlockPos(0, 0, 0)));
        assertTrue(SceneMeshBuilder.containsLocalGeometry(source, new BlockPos(2, 1, 3)));

        assertFalse(SceneMeshBuilder.containsLocalGeometry(source, new BlockPos(-1, 0, 0)),
                "the captured light halo must not participate in west-face culling");
        assertFalse(SceneMeshBuilder.containsLocalGeometry(source, new BlockPos(3, 1, 3)),
                "the captured light halo must not participate in east-face culling");
        assertFalse(SceneMeshBuilder.containsLocalGeometry(source, new BlockPos(2, 2, 3)),
                "the max-exclusive selection boundary must be treated as air for geometry");
        assertFalse(SceneMeshBuilder.containsLocalGeometry(source, new BlockPos(2, 1, 4)),
                "the captured light halo must not participate in south-face culling");
    }

    @Test
    public void testMeshBuildResultRecord() {
        SceneMeshSet set = new SceneMeshSet();
        SceneCompatibilityReport report = new SceneCompatibilityReport();
        SceneMeshBuilder.MeshBuildResult result = new SceneMeshBuilder.MeshBuildResult(set, report);

        assertSame(set, result.meshSet());
        assertSame(report, result.report());
    }

    @Test
    public void testResourceReloadListener() {
        SceneResourceReloadListener listener = new SceneResourceReloadListener();
        assertNotNull(listener.getFabricId());
        assertEquals("habitrain_core", listener.getFabricId().getNamespace());
        assertEquals("scene_mesh_reload", listener.getFabricId().getPath());
    }

    @Test
    public void testSceneMaterialSinkDefaultDelegates() {
        AtomicReference<ResourceLocation> quadTex = new AtomicReference<>();
        AtomicReference<ResourceLocation> triTex = new AtomicReference<>();

        SceneMaterialSink sink = new SceneMaterialSink() {
            @Override
            public void emitQuad(ResourceLocation texture, SceneRenderPayload.VisualVertex v0, SceneRenderPayload.VisualVertex v1, SceneRenderPayload.VisualVertex v2, SceneRenderPayload.VisualVertex v3) {
                quadTex.set(texture);
            }

            @Override
            public void emitTriangle(ResourceLocation texture, SceneRenderPayload.VisualVertex v0, SceneRenderPayload.VisualVertex v1, SceneRenderPayload.VisualVertex v2) {
                triTex.set(texture);
            }
        };

        ResourceLocation testTex = ResourceLocation.parse("test:my_texture");
        SceneMaterialKey key = SceneMaterialKey.direct(testTex, SceneMaterialKey.BlendMode.SOLID, SceneMaterialKey.PrimitiveMode.QUADS);

        SceneRenderPayload.VisualVertex v0 = SceneRenderPayload.VisualVertex.of(0, 0, 0, 0, 0, 0);
        SceneRenderPayload.VisualVertex v1 = SceneRenderPayload.VisualVertex.of(1, 0, 0, 1, 0, 0);
        SceneRenderPayload.VisualVertex v2 = SceneRenderPayload.VisualVertex.of(1, 1, 0, 1, 1, 0);
        SceneRenderPayload.VisualVertex v3 = SceneRenderPayload.VisualVertex.of(0, 1, 0, 0, 1, 0);

        sink.emitQuad(key, v0, v1, v2, v3);
        assertEquals(testTex, quadTex.get());

        sink.emitTriangle(key, v0, v1, v2);
        assertEquals(testTex, triTex.get());
    }

    @Test
    public void testFabricMaterialMappingKeepsActualBlendAndEmissiveState() {
        SceneMaterialKey translucent = SceneMeshBuilder.fabricMaterialKey(
                "TRANSLUCENT", true, SceneMeshSet.Layer.SOLID);
        assertEquals(SceneMaterialKey.BlendMode.TRANSLUCENT, translucent.blendMode());
        assertEquals(SceneMaterialKey.ShaderFamily.TRANSLUCENT, translucent.shaderFamily());
        assertEquals(SceneMaterialKey.UvSpace.BLOCK_ATLAS, translucent.uvSpace());
        assertEquals(SceneMaterialKey.PrimitiveMode.QUADS, translucent.primitiveMode());
        assertTrue(translucent.emissive());

        SceneMaterialKey defaultCutout = SceneMeshBuilder.fabricMaterialKey(
                "DEFAULT", false, SceneMeshSet.Layer.CUTOUT);
        assertEquals(SceneMaterialKey.BlendMode.CUTOUT, defaultCutout.blendMode());
        assertFalse(defaultCutout.mipmap());
        assertFalse(defaultCutout.emissive());

        assertThrows(IllegalArgumentException.class,
                () -> SceneMeshBuilder.fabricMaterialKey("PRIVATE_SHADER", false, SceneMeshSet.Layer.SOLID));
    }

    @Test
    public void testFabricVertexLightingAndColorMath() {
        assertEquals(0x00A000C0,
                SceneMeshBuilder.mergePackedLight(0x00A00050, 0x003000C0));
        assertEquals(0, SceneMeshBuilder.mergePackedLight(0, 0),
                "zero packed light is legitimate darkness and must not become full-bright");

        assertEquals(0x80404010,
                SceneMeshBuilder.multiplyArgb(0x8080FF40, 0x00804040));
        assertEquals(0x80202008,
                SceneMeshBuilder.shadeArgb(0x80404010, 0.5f));
    }
}
