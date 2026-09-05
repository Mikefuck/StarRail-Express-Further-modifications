package com.habitrain.core.api.client.scene.compat;

import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 客户端静态烘焙上下文。
 *
 * @param localPos 场景局部方块坐标
 * @param state 方块状态
 * @param payload 从资产快照解码的安全视觉载荷（可为 null）
 * @param blockView 场景只读快照视口（支持光照、变色和邻居状态读取）
 */
@Environment(EnvType.CLIENT)
public record SceneBakeContext(
        BlockPos localPos,
        BlockState state,
        SceneRenderPayload payload,
        BlockAndTintGetter blockView
) {}
