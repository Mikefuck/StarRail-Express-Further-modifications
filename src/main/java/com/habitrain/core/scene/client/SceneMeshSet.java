package com.habitrain.core.scene.client;

import com.habitrain.core.api.client.scene.compat.SceneMaterialKey;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
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
    private final Map<Layer, SortedBuffer> layerSorts = new EnumMap<>(Layer.class);
    private final Map<SceneMaterialKey, SortedBuffer> customSorts = new LinkedHashMap<>();
    private ByteBufferBuilder sortScratch;
    private static final class SortedBuffer {
        final SceneTransparencySort state;
        float x = Float.NaN, y, z;
        SortedBuffer(SceneTransparencySort state) { this.state = state; }
    }
    public void setSortState(Layer layer, SceneMaterialKey material, SceneTransparencySort sort) {
        if (sort == null) return;
        if (material == null) layerSorts.put(layer, new SortedBuffer(sort));
        else customSorts.put(material, new SortedBuffer(sort));
    }
    private void sort(VertexBuffer buffer, SortedBuffer sort) {
        if (sort == null) return;
        var camera = SceneShaders.localCamera();
        if (Float.isFinite(sort.x) && camera.distanceSquared(sort.x, sort.y, sort.z) < 1.0e-6f) return;
        if (sortScratch == null) sortScratch = new ByteBufferBuilder(4096);
        try (var indices = sort.state.buildSortedIndexBuffer(sortScratch,
                VertexSorting.byDistance(camera.x, camera.y, camera.z))) {
            if (indices != null) buffer.uploadIndexBuffer(indices);
        } finally {
            sortScratch.clear();
        }
        sort.x = camera.x; sort.y = camera.y; sort.z = camera.z;
    }
    private volatile boolean closed = false;
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
        renderLayer(layer, modelViewMatrix, projectionMatrix, renderType, 0.0f, 0.0f, 0.0f);
    }

    public void renderLayer(Layer layer, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, RenderType renderType,
                            float chunkOffsetX, float chunkOffsetY, float chunkOffsetZ) {
        if (closed) return;
        VertexBuffer buffer = buffers.get(layer);
        if (buffer == null) return;

        renderType.setupRenderState();
        try {
            buffer.bind();
            sort(buffer, layerSorts.get(layer));
            drawBufferWithChunkOffset(buffer, modelViewMatrix, projectionMatrix, chunkOffsetX, chunkOffsetY, chunkOffsetZ,
                    layer == Layer.CUTOUT_MIPPED ? 0.5f : layer == Layer.CUTOUT ? 0.1f : 0.0f);
        } finally {
            VertexBuffer.unbind();
            renderType.clearRenderState();
        }
    }

    public void renderCustomBatches(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, boolean renderTranslucent) {
        renderCustomBatches(modelViewMatrix, projectionMatrix, renderTranslucent, false, 0.0f, 0.0f, 0.0f);
    }

    /** Draw only opaque/cutout custom-material batches. */
    public void renderCustomOpaqueBatches(Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        renderCustomBatches(modelViewMatrix, projectionMatrix, false, false, 0.0f, 0.0f, 0.0f);
    }

    public void renderCustomOpaqueBatches(Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                                          float chunkOffsetX, float chunkOffsetY, float chunkOffsetZ) {
        renderCustomBatches(modelViewMatrix, projectionMatrix, false, false, chunkOffsetX, chunkOffsetY, chunkOffsetZ);
    }

    /** Draw only translucent custom-material batches. */
    public void renderCustomTranslucentBatches(Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        renderCustomBatches(modelViewMatrix, projectionMatrix, true, true, 0.0f, 0.0f, 0.0f);
    }

    public void renderCustomTranslucentBatches(Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                                               float chunkOffsetX, float chunkOffsetY, float chunkOffsetZ) {
        renderCustomBatches(modelViewMatrix, projectionMatrix, true, true, chunkOffsetX, chunkOffsetY, chunkOffsetZ);
    }

    private void renderCustomBatches(Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                                     boolean renderTranslucent, boolean translucentOnly,
                                     float chunkOffsetX, float chunkOffsetY, float chunkOffsetZ) {
        if (closed || customBuffers.isEmpty()) return;
        for (Map.Entry<SceneMaterialKey, VertexBuffer> entry : customBuffers.entrySet()) {
            SceneMaterialKey key = entry.getKey();
            VertexBuffer buffer = entry.getValue();
            if (buffer == null) continue;
            if (translucentOnly && !key.isTranslucent()) continue;
            if (key.isTranslucent() && !renderTranslucent) continue;

            RenderType renderType = key.toRenderType();
            renderType.setupRenderState();
            try {
                buffer.bind();
                sort(buffer, customSorts.get(key));
                drawBufferWithChunkOffset(buffer, modelViewMatrix, projectionMatrix, chunkOffsetX, chunkOffsetY, chunkOffsetZ,
                        key.blendMode() == SceneMaterialKey.BlendMode.CUTOUT_MIPPED ? 0.5f
                                : key.blendMode() == SceneMaterialKey.BlendMode.CUTOUT ? 0.1f : 0.0f);
            } finally {
                VertexBuffer.unbind();
                renderType.clearRenderState();
            }
        }
    }

    private static void drawBufferWithChunkOffset(VertexBuffer buffer, Matrix4f modelViewMatrix,
                                                  Matrix4f projectionMatrix,
                                                  float chunkOffsetX, float chunkOffsetY, float chunkOffsetZ, float alphaCutoff) {
        ShaderInstance original = RenderSystem.getShader();
        ShaderInstance shader = SceneShaders.select(original, alphaCutoff);
        if (shader != original) {
            buffer.drawWithShader(modelViewMatrix, projectionMatrix, shader);
            return;
        }
        if (shader != null && shader.CHUNK_OFFSET != null
                && (chunkOffsetX != 0.0f || chunkOffsetY != 0.0f || chunkOffsetZ != 0.0f)) {
            shader.CHUNK_OFFSET.set(chunkOffsetX, chunkOffsetY, chunkOffsetZ);
            try {
                Matrix4f compensatedModelView = new Matrix4f(modelViewMatrix)
                        .translate(-chunkOffsetX, -chunkOffsetY, -chunkOffsetZ);
                buffer.drawWithShader(compensatedModelView, projectionMatrix, shader);
            } finally {
                shader.CHUNK_OFFSET.set(0.0f, 0.0f, 0.0f);
            }
        } else {
            if (shader != null && shader.CHUNK_OFFSET != null) {
                shader.CHUNK_OFFSET.set(0.0f, 0.0f, 0.0f);
            }
            buffer.drawWithShader(modelViewMatrix, projectionMatrix, shader);
        }
    }

    @Override
    public void close() {
        List<Batch> children;
        synchronized (this) {
            if (closed) return;
            closed = true;
            children = new ArrayList<>(batches);
            batches.clear();
        }
        for (Batch batch : children) batch.mesh().close();
        // Logical closure is immediate, including child meshes. Only actual GL/native
        // objects need deferred destruction; empty value-only sets also work in headless tests.
        if (buffers.isEmpty() && customBuffers.isEmpty() && sortScratch == null) {
            layerSorts.clear(); customSorts.clear();
            return;
        }
        if (RenderSystem.isOnRenderThread()) releaseBuffers();
        else RenderSystem.recordRenderCall(this::releaseBuffers);
    }

    private synchronized void releaseBuffers() {
        for (VertexBuffer buf : buffers.values()) {
            if (buf != null) {
                buf.close();
            }
        }
        buffers.clear();
        layerSorts.clear();
        customSorts.clear();
        if (sortScratch != null) { sortScratch.close(); sortScratch = null; }
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
