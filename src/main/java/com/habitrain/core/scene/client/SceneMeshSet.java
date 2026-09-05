package com.habitrain.core.scene.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 场景网格 GPU 缓冲集合（支持原版 RenderType 快速层及可扩展 SceneMaterialKey 材质批次）。
 */
public final class SceneMeshSet implements AutoCloseable {
    public enum Layer {
        SOLID,
        CUTOUT_MIPPED,
        CUTOUT,
        TRANSLUCENT
    }

    private final Map<Layer, VertexBuffer> buffers = new EnumMap<>(Layer.class);
    private final Map<SceneMaterialKey, VertexBuffer> customBuffers = new LinkedHashMap<>();
    private boolean closed = false;

    public void setBuffer(Layer layer, VertexBuffer buffer) {
        VertexBuffer old = buffers.put(layer, buffer);
        if (old != null) {
            old.close();
        }
    }

    public VertexBuffer getBuffer(Layer layer) {
        return buffers.get(layer);
    }

    public void setCustomBuffer(SceneMaterialKey materialKey, VertexBuffer buffer) {
        if (materialKey == null) return;
        VertexBuffer old = customBuffers.put(materialKey, buffer);
        if (old != null) {
            old.close();
        }
    }

    public VertexBuffer getCustomBuffer(SceneMaterialKey materialKey) {
        return customBuffers.get(materialKey);
    }

    public Map<SceneMaterialKey, VertexBuffer> getCustomBuffers() {
        return Collections.unmodifiableMap(customBuffers);
    }

    public boolean isEmpty() {
        return buffers.isEmpty() && customBuffers.isEmpty();
    }

    public void renderLayer(Layer layer, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, RenderType renderType) {
        if (closed) return;
        VertexBuffer buffer = buffers.get(layer);
        if (buffer == null) return;

        renderType.setupRenderState();
        buffer.bind();
        buffer.drawWithShader(modelViewMatrix, projectionMatrix, RenderSystem.getShader());
        VertexBuffer.unbind();
        renderType.clearRenderState();
    }

    public void renderCustomBatches(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, boolean renderTranslucent) {
        renderCustomBatches(modelViewMatrix, projectionMatrix, renderTranslucent, false);
    }

    /** Draw only opaque/cutout custom-material batches. */
    public void renderCustomOpaqueBatches(Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        renderCustomBatches(modelViewMatrix, projectionMatrix, false, false);
    }

    /** Draw only translucent custom-material batches. */
    public void renderCustomTranslucentBatches(Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        renderCustomBatches(modelViewMatrix, projectionMatrix, true, true);
    }

    private void renderCustomBatches(Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                                     boolean renderTranslucent, boolean translucentOnly) {
        if (closed || customBuffers.isEmpty()) return;
        for (Map.Entry<SceneMaterialKey, VertexBuffer> entry : customBuffers.entrySet()) {
            SceneMaterialKey key = entry.getKey();
            VertexBuffer buffer = entry.getValue();
            if (buffer == null) continue;
            if (translucentOnly && !key.isTranslucent()) continue;
            if (key.isTranslucent() && !renderTranslucent) continue;

            RenderType renderType = key.toRenderType();
            renderType.setupRenderState();
            buffer.bind();
            buffer.drawWithShader(modelViewMatrix, projectionMatrix, RenderSystem.getShader());
            VertexBuffer.unbind();
            renderType.clearRenderState();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (VertexBuffer buf : buffers.values()) {
            if (buf != null) {
                buf.close();
            }
        }
        buffers.clear();
        for (VertexBuffer buf : customBuffers.values()) {
            if (buf != null) {
                buf.close();
            }
        }
        customBuffers.clear();
    }

    public boolean isClosed() {
        return closed;
    }
}
