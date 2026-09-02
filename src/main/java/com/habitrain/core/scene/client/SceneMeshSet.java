package com.habitrain.core.scene.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.Map;

/**
 * 场景网格 GPU 缓冲集合（分 RenderType 存放已上传到显存的 VertexBuffer）。
 */
public final class SceneMeshSet implements AutoCloseable {
    public enum Layer {
        SOLID,
        CUTOUT_MIPPED,
        CUTOUT,
        TRANSLUCENT
    }

    private final Map<Layer, VertexBuffer> buffers = new EnumMap<>(Layer.class);
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

    public boolean isEmpty() {
        return buffers.isEmpty();
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
    }

    public boolean isClosed() {
        return closed;
    }
}
