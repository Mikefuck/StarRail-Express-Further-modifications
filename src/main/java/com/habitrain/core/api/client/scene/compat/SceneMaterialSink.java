package com.habitrain.core.api.client.scene.compat;

import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import com.habitrain.core.scene.client.SceneMaterialKey;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

/**
 * 接收第三方方块适配器发射的几何面与材质的接收槽。
 */
@Environment(EnvType.CLIENT)
public interface SceneMaterialSink {
    /** 向指定材质批次发射一个四边形面（4 个顶点） */
    default void emitQuad(SceneMaterialKey material,
                          SceneRenderPayload.VisualVertex v0,
                          SceneRenderPayload.VisualVertex v1,
                          SceneRenderPayload.VisualVertex v2,
                          SceneRenderPayload.VisualVertex v3) {
        emitQuad(material != null ? material.textureOrAtlasId() : null, v0, v1, v2, v3);
    }

    /** 向指定材质批次发射一个三角形面（3 个顶点） */
    default void emitTriangle(SceneMaterialKey material,
                              SceneRenderPayload.VisualVertex v0,
                              SceneRenderPayload.VisualVertex v1,
                              SceneRenderPayload.VisualVertex v2) {
        emitTriangle(material != null ? material.textureOrAtlasId() : null, v0, v1, v2);
    }

    /** 发射一个四边形面（4 个顶点） */
    void emitQuad(ResourceLocation texture,
                  SceneRenderPayload.VisualVertex v0,
                  SceneRenderPayload.VisualVertex v1,
                  SceneRenderPayload.VisualVertex v2,
                  SceneRenderPayload.VisualVertex v3);

    /** 发射一个三角形面（3 个顶点） */
    void emitTriangle(ResourceLocation texture,
                      SceneRenderPayload.VisualVertex v0,
                      SceneRenderPayload.VisualVertex v1,
                      SceneRenderPayload.VisualVertex v2);
}
