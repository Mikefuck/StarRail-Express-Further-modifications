package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SceneMeshSet} 的批次容器语义：**未分批时必须与历史行为完全一致**，分批时释放与
 * 记账都要覆盖到子批次（漏掉一个就是一次显存泄漏）。
 *
 * <p>这里用空网格构造批次：{@code VertexBuffer} 需要 GL 上下文，而本测试要验证的是容器行为，
 * 不是上传行为。</p>
 */
class SceneMeshSetBatchTest {

    private static SceneMeshSet.Batch batch(SceneMeshSet mesh, double min, double max) {
        return new SceneMeshSet.Batch(mesh, min, 0, 0, max, 64, 64);
    }

    @Test
    void freshSetIsUnbatchedAndEmpty() {
        SceneMeshSet set = new SceneMeshSet();
        assertFalse(set.isBatched());
        assertTrue(set.isEmpty());
        assertTrue(set.batches().isEmpty());
        set.close();
    }

    @Test
    void batchesMakeTheSetNonEmpty() {
        SceneMeshSet set = new SceneMeshSet();
        set.addBatch(batch(new SceneMeshSet(), 0, 128));
        assertTrue(set.isBatched());
        assertFalse(set.isEmpty());
        assertEquals(1, set.batches().size());
        set.close();
    }

    @Test
    void nullBatchesAreIgnored() {
        SceneMeshSet set = new SceneMeshSet();
        set.addBatch(null);
        set.addBatch(batch(null, 0, 1));
        assertFalse(set.isBatched());
        set.close();
    }

    @Test
    void estimatedBytesSumChildren() {
        SceneMeshSet outer = new SceneMeshSet();
        SceneMeshSet first = new SceneMeshSet();
        first.setEstimatedBytes(1000L);
        SceneMeshSet second = new SceneMeshSet();
        second.setEstimatedBytes(2500L);
        outer.setEstimatedBytes(500L);
        outer.addBatch(batch(first, 0, 128));
        outer.addBatch(batch(second, 128, 256));

        assertEquals(4000L, outer.estimatedBytes(), "外层自己的估算 + 各批次之和");

        // 批次单独释放后不再计入总量：否则网格预算会越用越"满"。
        second.close();
        assertEquals(1500L, outer.estimatedBytes());
        outer.close();
    }

    @Test
    void closeClosesBatches() {
        SceneMeshSet outer = new SceneMeshSet();
        SceneMeshSet child = new SceneMeshSet();
        outer.addBatch(batch(child, 0, 128));

        outer.close();
        assertTrue(outer.isClosed());
        assertTrue(child.isClosed(), "外层释放必须带上子批次");
        assertTrue(outer.batches().isEmpty());
        assertEquals(0L, outer.estimatedBytes());
    }

    @Test
    void batchesViewIsUnmodifiable() {
        SceneMeshSet set = new SceneMeshSet();
        set.addBatch(batch(new SceneMeshSet(), 0, 128));
        assertThrows(UnsupportedOperationException.class, () -> set.batches().clear());
        set.close();
    }

    @Test
    void batchGeometryHelpersAreConsistent() {
        SceneMeshSet.Batch b = new SceneMeshSet.Batch(new SceneMeshSet(),
                0, 0, 0, 16, 16, 16);
        assertEquals(8.0, b.centerX());
        assertEquals(8.0, b.centerY());
        assertEquals(8.0, b.centerZ());
        assertEquals(0.5 * Math.sqrt(3 * 16.0 * 16.0), b.radius(), 1e-9);
    }

    /** 未分批时两个渲染入口必须还能用（它们是历史调用点，不能因为分批而失效）。 */
    @Test
    void flatAccessorsStillWorkWithoutBatches() {
        SceneMeshSet set = new SceneMeshSet();
        assertNull(set.getBuffer(SceneMeshSet.Layer.SOLID));
        assertNull(set.getCustomBuffer(null));
        assertTrue(set.getCustomBuffers().isEmpty());
        assertDoesNotThrow(() -> set.setCustomBuffer(null, null));
        set.close();
    }
}
