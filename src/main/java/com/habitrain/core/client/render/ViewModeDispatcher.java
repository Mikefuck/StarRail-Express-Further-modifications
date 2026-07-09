package com.habitrain.core.client.render;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.mixin.CustomTaskBlockRendererMixin;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import io.wifi.starrailexpress.content.block.api.TaskInstinctShowableInterface;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.awt.Color;
import java.util.Map;
import java.util.Set;

/**
 * 旁观/创造模式下的方块高亮渲染调度。
 *
 * 渲染所有已注册的自定义任务方块（类型 ≥12）。
 * 对应原始 {@code TaskBlockOverlayRenderer.render()} 中旁观/创造模式
 * 下 {@code shouldDisplay[1..11] = true} 的行为。
 *
 * 注意：旁观模式没有"活跃任务"，因此描边粗细使用 SRE 默认值 4.0。
 */
@Environment(EnvType.CLIENT)
public final class ViewModeDispatcher {

    /** 旁观/创造模式默认线宽 */
    private static final float SPECTATOR_LINE_WIDTH = 4.0f;

    private ViewModeDispatcher() {}

    /**
     * 渲染所有自定义任务方块的边框。
     *
     * @param renderContext 渲染上下文
     */
    public static void renderAll(WorldRenderContext renderContext) {
        // 大厅阶段（无活跃游戏）→ 不渲染 DLC 自定义方块（type ≥ 12）。
        // 只让 SRE 原版渲染器处理原版方块（type 1-11）。
        if (!GameRunningCache.isGameRunning()) {
            return;
        }

        Map<Integer, Color> typeColors = TypeColorMapper.buildTypeColorMap();
        if (typeColors.isEmpty()) return;

        int renderedCount = 0;
        var level = renderContext.world();
        for (BlockPos pos : CustomTaskBlockCache.keySet()) {
            Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
            if (typeIds == null) continue;

            // 性能优化：优先读缓存的 Block，避免每位置每帧 level.getBlockState(pos)
            Block cachedBlock = CustomTaskBlockCache.getBlockAt(pos);
            Block block = cachedBlock;
            if (block == null && level != null) {
                block = level.getBlockState(pos).getBlock();
            }
            if (block != null && block instanceof TaskInstinctShowableInterface) {
                continue;
            }

            for (int type : typeIds) {
                if (type == 12) continue;
                Color color = typeColors.get(type);
                if (color != null) {
                    CustomTaskBlockRendererMixin.renderCustomOverlay(
                            renderContext, pos, color, SPECTATOR_LINE_WIDTH);
                    renderedCount++;
                    break;
                }
            }
        }

        if (renderedCount > 0) {
            HabiTrainCore.LOGGER.debug(
                    "[ViewModeDispatcher] rendered {} custom task blocks (spectating/creative)", renderedCount);
        }
    }
}
