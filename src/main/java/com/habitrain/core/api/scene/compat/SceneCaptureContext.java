package com.habitrain.core.api.scene.compat;

import com.habitrain.core.scene.model.SceneBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 场景捕获期间传递给 {@link SceneBlockCaptureAdapter} 的上下文。
 *
 * @param level 当前服务端世界
 * @param worldPos 方块在世界中的绝对坐标
 * @param localPos 方块相对于场景包围盒最小角的局部坐标
 * @param state 方块状态
 * @param blockEntity 方块实体（若无则为 null）
 * @param visibleBounds 当前正在捕获的场景可见包围盒
 */
public record SceneCaptureContext(
        ServerLevel level,
        BlockPos worldPos,
        BlockPos localPos,
        BlockState state,
        BlockEntity blockEntity,
        SceneBounds visibleBounds
) {}
