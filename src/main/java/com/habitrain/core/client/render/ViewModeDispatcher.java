package com.habitrain.core.client.render;

import com.habitrain.core.HabiTrainCore;
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
 * 描边粗细使用 SRE 默认值 4.0。
 */
@Environment(EnvType.CLIENT)
public final class ViewModeDispatcher {

    private static final float SPECTATOR_LINE_WIDTH = 4.0f;

    private ViewModeDispatcher() {}

    public static void renderAll(WorldRenderContext renderContext) {
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
                    TaskOverlayDrawer.renderOverlay(renderContext, pos, color, SPECTATOR_LINE_WIDTH);
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
