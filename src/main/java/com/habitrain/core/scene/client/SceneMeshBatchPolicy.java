package com.habitrain.core.scene.client;

import com.habitrain.core.api.scene.model.SceneBounds;

/**
 * 网格分批策略：**只有足够大的场景才分批**，其余保持与历史完全一致的单份网格。
 *
 * <p>为什么要有阈值：分批不是白拿的。每多一批就多出若干次 draw call 与
 * {@code setupRenderState}/{@code clearRenderState} 状态切换，还会多出一批 CPU 侧的
 * {@code ByteBufferBuilder} 原生分配。真实资产最大才 64 个 Section（约几万 quad），
 * 这种量级下"多切几刀省下的顶点"根本盖不过"多出来的状态切换"。</p>
 *
 * <p>切分轴取包围盒最长的轴，且只用几何信息、不看 profile：网格是按资产构建的，
 * 而运动方向属于配置，改速度/轨道不该重建网格。</p>
 */
public final class SceneMeshBatchPolicy {

    public static final int MAX_BATCHES = 8;
    /** 每翻一倍 Section 数就把批次数翻一倍，直到 {@link #MAX_BATCHES}。 */
    public static final int SECTIONS_PER_STEP = 512;

    private SceneMeshBatchPolicy() {}

    /**
     * 分批计划。
     *
     * @param batchCount 批次数，1 表示不分批
     * @param axis       切分轴：0=x、1=y、2=z
     * @param splits     批次边界（本地坐标，长度 = batchCount + 1）
     */
    public record Plan(int batchCount, int axis, int[] splits) {
        public boolean isBatched() {
            return batchCount > 1;
        }

        /** 某个本地坐标落在哪一批。 */
        public int batchIndex(int localAlongAxis) {
            for (int i = batchCount - 1; i >= 0; i--) {
                if (localAlongAxis >= splits[i]) return i;
            }
            return 0;
        }
    }

    public static Plan single() {
        return new Plan(1, 0, new int[]{0, 0});
    }

    /**
     * 按 Section 数与包围盒决定分批。
     *
     * @param sectionCount        资产里的 Section 数（空 Section 已被捕获端跳过）
     * @param minSectionsPerBatch 每多少 Section 才多切一刀；&le; 0 表示关闭分批
     * @param bounds              资产源范围（本地坐标的尺寸来源）
     */
    public static Plan plan(int sectionCount, int minSectionsPerBatch, SceneBounds bounds) {
        if (bounds == null || bounds.isEmpty()) return single();
        if (minSectionsPerBatch <= 0 || sectionCount < minSectionsPerBatch) return single();

        int steps = sectionCount / minSectionsPerBatch;      // 1,2,3,...
        int count = 1;
        while (count * 2 <= MAX_BATCHES && count < steps * 2) {
            count *= 2;
        }
        if (count <= 1) return single();

        int axis = longestAxis(bounds);
        int length = axisLength(bounds, axis);
        if (length <= count) return single();

        int[] splits = new int[count + 1];
        for (int i = 0; i <= count; i++) {
            splits[i] = (int) ((long) length * i / count);
        }
        return new Plan(count, axis, splits);
    }

    private static int longestAxis(SceneBounds bounds) {
        long x = bounds.sizeX();
        long y = bounds.sizeY();
        long z = bounds.sizeZ();
        if (x >= y && x >= z) return 0;
        return y >= z ? 1 : 2;
    }

    private static int axisLength(SceneBounds bounds, int axis) {
        return switch (axis) {
            case 1 -> bounds.sizeY();
            case 2 -> bounds.sizeZ();
            default -> bounds.sizeX();
        };
    }

    /** 某个本地坐标在切分轴上的分量。 */
    public static int axisCoordinate(Plan plan, int localX, int localY, int localZ) {
        return switch (plan.axis()) {
            case 1 -> localY;
            case 2 -> localZ;
            default -> localX;
        };
    }

    /**
     * 某一批在本地坐标下的 AABB 半开区间 {@code [from, to)}，用于逐批裁剪。
     */
    public static int[] batchRange(Plan plan, int batchIndex) {
        int index = Math.max(0, Math.min(plan.batchCount() - 1, batchIndex));
        return new int[]{plan.splits()[index], plan.splits()[index + 1]};
    }
}
