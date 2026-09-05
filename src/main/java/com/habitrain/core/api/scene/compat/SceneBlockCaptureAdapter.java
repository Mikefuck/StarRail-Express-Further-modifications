package com.habitrain.core.api.scene.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 移动场景服务端方块捕获适配器。
 * <p>
 * 在场景捕获阶段被调用，负责从复杂模型或方块实体中提取重建视觉所需的受限 {@link SceneRenderPayload}。
 * 严格禁止返回容器物品、战利品表、命令等非视觉数据。
 */
public interface SceneBlockCaptureAdapter {
    /** 适配器唯一标识符 */
    ResourceLocation adapterId();

    /** 适配器导出的数据格式版本号 */
    int dataVersion();

    /** 判断本适配器是否支持该方块或方块实体 */
    boolean supports(BlockState state, BlockEntity blockEntity);

    /** 提取安全视觉数据载荷；若无需特殊处理可返回 null */
    SceneRenderPayload captureVisualData(SceneCaptureContext context);
}
