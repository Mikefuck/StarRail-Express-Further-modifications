package com.habitrain.core.scene.client;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SceneMaterialKeyTest {

    @Test
    public void testStandardLayerConstants() {
        assertNotNull(SceneMaterialKey.SOLID);
        assertEquals(TextureAtlas.LOCATION_BLOCKS, SceneMaterialKey.SOLID.textureOrAtlasId());
        assertEquals(SceneMaterialKey.UvSpace.BLOCK_ATLAS, SceneMaterialKey.SOLID.uvSpace());
        assertEquals(SceneMaterialKey.BlendMode.SOLID, SceneMaterialKey.SOLID.blendMode());
        assertTrue(SceneMaterialKey.SOLID.cull());
        assertFalse(SceneMaterialKey.SOLID.isTranslucent());
        assertEquals(SceneMaterialKey.PrimitiveMode.QUADS, SceneMaterialKey.SOLID.primitiveMode());
        assertEquals(VertexFormat.Mode.QUADS, SceneMaterialKey.SOLID.vertexFormatMode());

        assertNotNull(SceneMaterialKey.TRANSLUCENT);
        assertTrue(SceneMaterialKey.TRANSLUCENT.isTranslucent());
        assertEquals(SceneMaterialKey.BlendMode.TRANSLUCENT, SceneMaterialKey.TRANSLUCENT.blendMode());
        assertEquals(SceneMaterialKey.ShaderFamily.TRANSLUCENT, SceneMaterialKey.TRANSLUCENT.shaderFamily());
    }

    @Test
    public void testFromLayerMapping() {
        assertSame(SceneMaterialKey.SOLID, SceneMaterialKey.fromLayer(SceneMeshSet.Layer.SOLID));
        assertSame(SceneMaterialKey.CUTOUT_MIPPED, SceneMaterialKey.fromLayer(SceneMeshSet.Layer.CUTOUT_MIPPED));
        assertSame(SceneMaterialKey.CUTOUT, SceneMaterialKey.fromLayer(SceneMeshSet.Layer.CUTOUT));
        assertSame(SceneMaterialKey.TRANSLUCENT, SceneMaterialKey.fromLayer(SceneMeshSet.Layer.TRANSLUCENT));
        assertSame(SceneMaterialKey.SOLID, SceneMaterialKey.fromLayer(null));
    }

    @Test
    public void testDirectAndAtlasFactories() {
        ResourceLocation customTex = ResourceLocation.parse("mymod:textures/special_model.png");
        SceneMaterialKey directQuad = SceneMaterialKey.direct(customTex, SceneMaterialKey.BlendMode.SOLID, SceneMaterialKey.PrimitiveMode.QUADS);
        assertEquals(customTex, directQuad.textureOrAtlasId());
        assertEquals(SceneMaterialKey.UvSpace.DIRECT, directQuad.uvSpace());
        assertEquals(SceneMaterialKey.BlendMode.SOLID, directQuad.blendMode());
        assertEquals(VertexFormat.Mode.QUADS, directQuad.vertexFormatMode());
        assertFalse(directQuad.isTranslucent());

        SceneMaterialKey directTri = SceneMaterialKey.direct(customTex, SceneMaterialKey.BlendMode.TRANSLUCENT, SceneMaterialKey.PrimitiveMode.TRIANGLES);
        assertEquals(VertexFormat.Mode.TRIANGLES, directTri.vertexFormatMode());
        assertTrue(directTri.isTranslucent());
        assertEquals(SceneMaterialKey.ShaderFamily.TRANSLUCENT, directTri.shaderFamily());

        ResourceLocation customAtlas = ResourceLocation.parse("mymod:textures/atlas/machines.png");
        SceneMaterialKey atlasKey = SceneMaterialKey.atlas(customAtlas, SceneMaterialKey.BlendMode.ADDITIVE, SceneMaterialKey.PrimitiveMode.QUADS);
        assertEquals(customAtlas, atlasKey.textureOrAtlasId());
        assertEquals(SceneMaterialKey.UvSpace.BLOCK_ATLAS, atlasKey.uvSpace());
        assertTrue(atlasKey.isTranslucent());
        assertEquals(SceneMaterialKey.ShaderFamily.ADDITIVE, atlasKey.shaderFamily());

        SceneMaterialKey inferredAdditive = new SceneMaterialKey(
                customTex, SceneMaterialKey.UvSpace.DIRECT, SceneMaterialKey.BlendMode.ADDITIVE,
                true, false, false, SceneMaterialKey.PrimitiveMode.TRIANGLES, null);
        assertEquals(SceneMaterialKey.ShaderFamily.ADDITIVE, inferredAdditive.shaderFamily());
    }

    @Test
    public void testRenderTypeCachingAndClear() {
        assumeRenderTypeBootstrapped();

        SceneMaterialKey key1 = SceneMaterialKey.direct(
                ResourceLocation.parse("test:tex_a.png"),
                SceneMaterialKey.BlendMode.CUTOUT,
                SceneMaterialKey.PrimitiveMode.QUADS
        );

        RenderType type1 = key1.toRenderType();
        assertNotNull(type1);
        RenderType type2 = key1.toRenderType();
        assertSame(type1, type2, "RenderType should be cached for the same SceneMaterialKey");

        SceneMaterialKey.clearRenderTypeCache();
        RenderType type3 = key1.toRenderType();
        assertNotNull(type3);
    }

    private static void assumeRenderTypeBootstrapped() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Minecraft RenderType environment not bootstrapped in headless test runner");
        }
    }
}
