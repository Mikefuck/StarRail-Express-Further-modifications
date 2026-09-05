package com.habitrain.core.api.scene.compat;

import net.minecraft.resources.ResourceLocation;

/**
 * 带有场景局部坐标与适配器标识的方块视觉载荷条目。
 *
 * @param localX 相对于 sourceBounds.minX() 的局部 X 偏移
 * @param localY 相对于 sourceBounds.minY() 的局部 Y 偏移
 * @param localZ 相对于 sourceBounds.minZ() 的局部 Z 偏移
 * @param adapterId 生成该载荷的适配器 ID
 * @param adapterVersion 生成该载荷时的适配器数据版本
 * @param payload 安全视觉载荷数据
 */
public record SceneBlockPayloadEntry(
        int localX,
        int localY,
        int localZ,
        ResourceLocation adapterId,
        int adapterVersion,
        SceneRenderPayload payload
) {
    public SceneBlockPayloadEntry {
        if (adapterId == null) throw new IllegalArgumentException("adapterId cannot be null");
        if (payload == null) throw new IllegalArgumentException("payload cannot be null");
    }
}
