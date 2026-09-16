package com.habitrain.core.scene.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景网格 GPU 缓冲集合（支持原版 RenderType 快速层及可扩展 SceneMaterialKey 材质批次）。
 *
 * <p>{@link #estimatedBytes()} 是显存占用的估算值，由构建方在交付时写入。它只用于跨网格的
 * 总量预算比较，不做精确统计——精确值需要向驱动查询，代价远高于它带来的收益。</p>
 */
public final class SceneMeshSet implements AutoCloseable {
    public enum Layer {
        SOLID,
        CUTOUT_MIPPED,
        CUTOUT,
        TRANSLUCENT
    }

    /**
     * 一个空间批次：一批**不透明**几何，外加它在本地的轴对齐包围盒。
     *
     * <p>批次存在的唯一理由是裁剪：整个副本一个包围球时，长条场景只要有一角在视锥里就会
     * 整份提交；拆成几段之后，视锥外那几段可以整段不提交。</p>
     *
     * <p><b>半透明层不参与分批</b>：GL 混合与绘制顺序有关，而分批把发射序切成了几段，
     * 批级排序比原来的逐顶点顺序粗。半透明整层留在外层集合里照旧画，代价是它不享受分段裁剪
     * （半透明通常只占场景的一小部分）。</p>
     */
    public record Batch(SceneMeshSet mesh, double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ) {
        public double centerX() { return (minX + maxX) * 0.5; }
        public double centerY() { return (minY + maxY) * 0.5; }
        public double centerZ() { return (minZ + maxZ) * 0.5; }

        public double radius() {
            double dx = maxX - minX;
            double dy = maxY - minY;
            double dz = maxZ - minZ;
            return 0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private final Map<Layer, VertexBuffer> buffers = new EnumMap<>(Layer.class);
    private final Map<SceneMaterialKey, VertexBuffer> customBuffers = new LinkedHashMap<>();
    /** 空表示未分批——此时这个集合就是整份网格，行为与分批功能上线前完全一致。 */
    private final List<Batch> batches = new ArrayList<>();
    private boolean closed = false;
    private long estimatedBytes;

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
        return buffers.isEmpty() && customBuffers.isEmpty() && batches.isEmpty();
    }

    /** 登记一个空间批次。分批与未分批互斥：登记过批次之后，外层只应再持有半透明层。 */
    public void addBatch(Batch batch) {
        if (batch != null && batch.mesh() != null) {
            batches.add(batch);
        }
    }

    public List<Batch> batches() {
        return Collections.unmodifiableList(batches);
    }

    /** 是否按空间分批（未分批 = 与历史行为逐字节一致的单份网格）。 */
    public boolean isBatched() {
        return !batches.isEmpty();
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
        for (Batch batch : batches) {
            batch.mesh().close();
        }
        batches.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    /** 构建方交付时写入的显存占用估算（字节）。 */
    public void setEstimatedBytes(long estimatedBytes) {
        this.estimatedBytes = Math.max(0L, estimatedBytes);
    }

    /** 已释放的网格按 0 计，避免汇总时把已关闭的网格继续算进预算。 */
    public long estimatedBytes() {
        if (closed) return 0L;
        long total = estimatedBytes;
        for (Batch batch : batches) {
            total += batch.mesh().estimatedBytes();
        }
        return total;
    }
}
