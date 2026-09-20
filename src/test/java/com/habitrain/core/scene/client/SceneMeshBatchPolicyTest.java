package com.habitrain.core.scene.client;

import com.habitrain.core.api.scene.model.SceneBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分批策略的边界：小场景必须保持单份网格（与历史行为一致），大场景才按最长轴切。
 */
class SceneMeshBatchPolicyTest {

    /** 64 格见方：真实资产里最大的那种（64 个 Section）。 */
    private static final SceneBounds SMALL = new SceneBounds(0, 0, 0, 64, 64, 64);
    /** 512×64×64：长条大场景，最长轴是 x。 */
    private static final SceneBounds LONG_X = new SceneBounds(0, 0, 0, 512, 64, 64);
    /** 64×64×512：最长轴是 z。 */
    private static final SceneBounds LONG_Z = new SceneBounds(0, 0, 0, 64, 64, 512);

    @Test
    void smallScenesStayUnbatched() {
        SceneMeshBatchPolicy.Plan plan = SceneMeshBatchPolicy.plan(64, 512, SMALL);
        assertFalse(plan.isBatched());
        assertEquals(1, plan.batchCount());
    }

    @Test
    void zeroOrNegativeThresholdDisablesBatching() {
        assertFalse(SceneMeshBatchPolicy.plan(8192, 0, LONG_X).isBatched());
        assertFalse(SceneMeshBatchPolicy.plan(8192, -5, LONG_X).isBatched());
    }

    @Test
    void emptyBoundsAreNeverBatched() {
        assertFalse(SceneMeshBatchPolicy.plan(8192, 1, SceneBounds.EMPTY).isBatched());
        assertFalse(SceneMeshBatchPolicy.plan(8192, 1, null).isBatched());
    }

    @Test
    void batchCountDoublesWithSectionsAndStopsAtTheCap() {
        assertEquals(2, SceneMeshBatchPolicy.plan(512, 512, LONG_X).batchCount());
        assertEquals(4, SceneMeshBatchPolicy.plan(1024, 512, LONG_X).batchCount());
        assertEquals(8, SceneMeshBatchPolicy.plan(2048, 512, LONG_X).batchCount());
        assertEquals(SceneMeshBatchPolicy.MAX_BATCHES,
                SceneMeshBatchPolicy.plan(8192, 512, LONG_X).batchCount(),
                "上限必须是硬上限：每多一批就多一批 draw call 与原生缓冲");
    }

    @Test
    void splitsCoverTheWholeAxisMonotonically() {
        SceneMeshBatchPolicy.Plan plan = SceneMeshBatchPolicy.plan(2048, 512, LONG_X);
        assertEquals(0, plan.axis());
        int[] splits = plan.splits();
        assertEquals(plan.batchCount() + 1, splits.length);
        assertEquals(0, splits[0]);
        assertEquals(512, splits[splits.length - 1], "最后一条边界必须落在轴长上");
        for (int i = 1; i < splits.length; i++) {
            assertTrue(splits[i] > splits[i - 1], "边界必须严格递增，否则会有空批次");
        }
    }

    @Test
    void splitAxisIsTheLongestAxis() {
        assertEquals(0, SceneMeshBatchPolicy.plan(2048, 512, LONG_X).axis());
        assertEquals(2, SceneMeshBatchPolicy.plan(2048, 512, LONG_Z).axis());
        SceneBounds tall = new SceneBounds(0, 0, 0, 64, 512, 64);
        assertEquals(1, SceneMeshBatchPolicy.plan(2048, 512, tall).axis());
    }

    @Test
    void batchIndexMapsCoordinatesToTheirSlab() {
        SceneMeshBatchPolicy.Plan plan = SceneMeshBatchPolicy.plan(1024, 512, LONG_X);
        assertEquals(4, plan.batchCount());
        assertEquals(0, plan.batchIndex(0));
        assertEquals(0, plan.batchIndex(127));
        assertEquals(1, plan.batchIndex(128));
        assertEquals(3, plan.batchIndex(511));
        assertEquals(3, plan.batchIndex(100000), "越界坐标夹到最后一批，不能返回负下标");
        assertEquals(0, plan.batchIndex(-5), "负坐标同样要夹住");
    }

    @Test
    void batchRangeIsHalfOpenAndClamped() {
        SceneMeshBatchPolicy.Plan plan = SceneMeshBatchPolicy.plan(1024, 512, LONG_X);
        int[] first = SceneMeshBatchPolicy.batchRange(plan, 0);
        int[] last = SceneMeshBatchPolicy.batchRange(plan, plan.batchCount() - 1);
        assertEquals(0, first[0]);
        assertEquals(512, last[1]);
        for (int i = 1; i < plan.batchCount(); i++) {
            int[] previous = SceneMeshBatchPolicy.batchRange(plan, i - 1);
            int[] current = SceneMeshBatchPolicy.batchRange(plan, i);
            assertEquals(previous[1], current[0], "相邻批次必须首尾相接，不能有缝");
        }
        assertEquals(last[1], SceneMeshBatchPolicy.batchRange(plan, 999)[1], "越界下标夹住");
    }

    @Test
    void axisCoordinateFollowsThePlanAxis() {
        SceneMeshBatchPolicy.Plan planX = SceneMeshBatchPolicy.plan(2048, 512, LONG_X);
        assertEquals(7, SceneMeshBatchPolicy.axisCoordinate(planX, 7, 3, 5));
        SceneMeshBatchPolicy.Plan planZ = SceneMeshBatchPolicy.plan(2048, 512, LONG_Z);
        assertEquals(5, SceneMeshBatchPolicy.axisCoordinate(planZ, 7, 3, 5));
    }

    /** 最长轴比批次数还短时切开只会得到空批次，不如不切。 */
    @Test
    void degenerateBoundsFallBackToSingle() {
        SceneBounds tiny = new SceneBounds(0, 0, 0, 4, 4, 4);
        assertFalse(SceneMeshBatchPolicy.plan(4096, 512, tiny).isBatched());
    }

    @Test
    void singlePlanIsNeutral() {
        SceneMeshBatchPolicy.Plan plan = SceneMeshBatchPolicy.single();
        assertEquals(1, plan.batchCount());
        assertFalse(plan.isBatched());
        assertEquals(0, plan.batchIndex(500));
    }
}
