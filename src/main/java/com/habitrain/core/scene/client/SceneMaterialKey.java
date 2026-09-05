package com.habitrain.core.scene.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景多材质批次键，用于区分不同纹理/图集、UV空间、混合模式、剔除、光照及着色器。
 */
@Environment(EnvType.CLIENT)
public record SceneMaterialKey(
        ResourceLocation textureOrAtlasId,
        UvSpace uvSpace,
        BlendMode blendMode,
        boolean cull,
        boolean mipmap,
        boolean emissive,
        PrimitiveMode primitiveMode,
        ShaderFamily shaderFamily
) {
    public enum UvSpace {
        BLOCK_ATLAS,
        DIRECT
    }

    public enum BlendMode {
        SOLID,
        CUTOUT,
        CUTOUT_MIPPED,
        TRANSLUCENT,
        ADDITIVE
    }

    public enum PrimitiveMode {
        QUADS(4),
        TRIANGLES(3);

        private final int verticesPerPolygon;

        PrimitiveMode(int verticesPerPolygon) {
            this.verticesPerPolygon = verticesPerPolygon;
        }

        public int verticesPerPolygon() {
            return verticesPerPolygon;
        }
    }

    public enum ShaderFamily {
        BLOCK,
        TRANSLUCENT,
        ADDITIVE
    }

    public static final SceneMaterialKey SOLID = new SceneMaterialKey(
            TextureAtlas.LOCATION_BLOCKS,
            UvSpace.BLOCK_ATLAS,
            BlendMode.SOLID,
            true,
            true,
            false,
            PrimitiveMode.QUADS,
            ShaderFamily.BLOCK
    );

    public static final SceneMaterialKey CUTOUT_MIPPED = new SceneMaterialKey(
            TextureAtlas.LOCATION_BLOCKS,
            UvSpace.BLOCK_ATLAS,
            BlendMode.CUTOUT_MIPPED,
            true,
            true,
            false,
            PrimitiveMode.QUADS,
            ShaderFamily.BLOCK
    );

    public static final SceneMaterialKey CUTOUT = new SceneMaterialKey(
            TextureAtlas.LOCATION_BLOCKS,
            UvSpace.BLOCK_ATLAS,
            BlendMode.CUTOUT,
            true,
            false,
            false,
            PrimitiveMode.QUADS,
            ShaderFamily.BLOCK
    );

    public static final SceneMaterialKey TRANSLUCENT = new SceneMaterialKey(
            TextureAtlas.LOCATION_BLOCKS,
            UvSpace.BLOCK_ATLAS,
            BlendMode.TRANSLUCENT,
            true,
            true,
            false,
            PrimitiveMode.QUADS,
            ShaderFamily.TRANSLUCENT
    );

    private static final Map<SceneMaterialKey, RenderType> RENDER_TYPE_CACHE = new ConcurrentHashMap<>();

    public SceneMaterialKey {
        if (textureOrAtlasId == null) textureOrAtlasId = TextureAtlas.LOCATION_BLOCKS;
        if (uvSpace == null) uvSpace = UvSpace.BLOCK_ATLAS;
        if (blendMode == null) blendMode = BlendMode.SOLID;
        if (primitiveMode == null) primitiveMode = PrimitiveMode.QUADS;
        if (shaderFamily == null) {
            shaderFamily = blendMode == BlendMode.ADDITIVE ? ShaderFamily.ADDITIVE
                    : blendMode == BlendMode.TRANSLUCENT ? ShaderFamily.TRANSLUCENT
                    : ShaderFamily.BLOCK;
        }
    }

    public static SceneMaterialKey fromLayer(SceneMeshSet.Layer layer) {
        if (layer == null) return SOLID;
        return switch (layer) {
            case SOLID -> SOLID;
            case CUTOUT_MIPPED -> CUTOUT_MIPPED;
            case CUTOUT -> CUTOUT;
            case TRANSLUCENT -> TRANSLUCENT;
        };
    }

    public static SceneMaterialKey direct(ResourceLocation texture, BlendMode blendMode, PrimitiveMode primitiveMode) {
        ShaderFamily shader = blendMode == BlendMode.ADDITIVE ? ShaderFamily.ADDITIVE
                : blendMode == BlendMode.TRANSLUCENT ? ShaderFamily.TRANSLUCENT : ShaderFamily.BLOCK;
        return new SceneMaterialKey(texture, UvSpace.DIRECT, blendMode, true, false, false, primitiveMode, shader);
    }

    public static SceneMaterialKey atlas(ResourceLocation atlas, BlendMode blendMode, PrimitiveMode primitiveMode) {
        ShaderFamily shader = blendMode == BlendMode.ADDITIVE ? ShaderFamily.ADDITIVE
                : blendMode == BlendMode.TRANSLUCENT ? ShaderFamily.TRANSLUCENT : ShaderFamily.BLOCK;
        return new SceneMaterialKey(atlas, UvSpace.BLOCK_ATLAS, blendMode, true, true, false, primitiveMode, shader);
    }

    public boolean isTranslucent() {
        return blendMode == BlendMode.TRANSLUCENT || blendMode == BlendMode.ADDITIVE;
    }

    public VertexFormat.Mode vertexFormatMode() {
        return primitiveMode == PrimitiveMode.TRIANGLES ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS;
    }

    public RenderType toRenderType() {
        return RENDER_TYPE_CACHE.computeIfAbsent(this, SceneMaterialKey::createRenderType);
    }

    public static void clearRenderTypeCache() {
        RENDER_TYPE_CACHE.clear();
    }

    private static RenderType createRenderType(SceneMaterialKey key) {
        ResourceLocation texture = key.textureOrAtlasId();
        String name = "scene_mat_" + texture.getNamespace() + "_" + texture.getPath().replace('/', '_')
                + "_" + key.blendMode().name().toLowerCase()
                + "_" + key.primitiveMode().name().toLowerCase()
                + (key.cull() ? "_cull" : "_nocull");

        RenderStateShard.ShaderStateShard shaderShard = switch (key.shaderFamily()) {
            case TRANSLUCENT -> RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER;
            case ADDITIVE -> RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER;
            case BLOCK -> switch (key.blendMode()) {
                case CUTOUT -> RenderStateShard.RENDERTYPE_CUTOUT_SHADER;
                case CUTOUT_MIPPED -> RenderStateShard.RENDERTYPE_CUTOUT_MIPPED_SHADER;
                case TRANSLUCENT, ADDITIVE -> RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER;
                case SOLID -> RenderStateShard.RENDERTYPE_SOLID_SHADER;
            };
        };

        RenderStateShard.TransparencyStateShard transparencyShard = switch (key.blendMode()) {
            case SOLID, CUTOUT, CUTOUT_MIPPED -> RenderStateShard.NO_TRANSPARENCY;
            case TRANSLUCENT -> RenderStateShard.TRANSLUCENT_TRANSPARENCY;
            case ADDITIVE -> RenderStateShard.ADDITIVE_TRANSPARENCY;
        };

        RenderStateShard.CullStateShard cullShard = key.cull()
                ? RenderStateShard.CULL
                : RenderStateShard.NO_CULL;

        RenderStateShard.TextureStateShard textureShard = new RenderStateShard.TextureStateShard(
                texture, false, key.mipmap()
        );

        return RenderType.create(
                name,
                DefaultVertexFormat.BLOCK,
                key.vertexFormatMode(),
                131072,
                false,
                key.isTranslucent(),
                RenderType.CompositeState.builder()
                        .setShaderState(shaderShard)
                        .setTextureState(textureShard)
                        .setTransparencyState(transparencyShard)
                        .setCullState(cullShard)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .createCompositeState(false)
        );
    }
}
