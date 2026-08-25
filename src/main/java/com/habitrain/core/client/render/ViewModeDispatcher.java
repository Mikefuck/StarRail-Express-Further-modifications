package com.habitrain.core.client.render;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import com.habitrain.core.game.sre.CustomTaskBlockIndexLimits;
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
 * Spectator/creative custom-task ESP.
 *
 * <p>Draws sparse overlay points (cats, backpacks, phones, generators, …) and
 * skips bulk decorative blocks so a coal-heavy map does not flood the pass.
 */
@Environment(EnvType.CLIENT)
public final class ViewModeDispatcher {

    private static final float SPECTATOR_LINE_WIDTH = 4.0f;
    private static final Color FALLBACK_COLOR = new Color(200, 200, 200, 180);

    private ViewModeDispatcher() {}

    public static void renderAll(WorldRenderContext renderContext) {
        if (!GameRunningCache.isGameRunning()) {
            return;
        }
        if (CustomTaskBlockCache.isEmpty()) {
            return;
        }

        var level = renderContext.world();
        Map<Integer, Color> colors = TypeColorMapper.buildTypeColorMap();
        int renderedCount = 0;
        for (BlockPos pos : CustomTaskBlockCache.keySet()) {
            Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
            Block block = CustomTaskBlockCache.getBlockAt(pos);
            if (block == null && level != null) {
                block = level.getBlockState(pos).getBlock();
            }
            if (!CustomTaskBlockIndexLimits.shouldDrawSpectatorOverlay(block, typeIds)) {
                continue;
            }
            if (block instanceof TaskInstinctShowableInterface) {
                continue;
            }
            if (!TaskOverlayDrawer.isInOverlayRange(
                    renderContext, pos, TaskOverlayDrawer.SPECTATOR_SPARSE_OVERLAY_DISTANCE_SQ)) {
                continue;
            }
            TaskOverlayDrawer.renderOverlay(
                    renderContext, pos, resolveColor(typeIds, colors), SPECTATOR_LINE_WIDTH);
            renderedCount++;
        }

        if (renderedCount > 0) {
            HabiTrainCore.LOGGER.debug(
                    "[ViewModeDispatcher] rendered {} sparse overlay blocks (spectating/creative)", renderedCount);
        }
    }

    private static Color resolveColor(Set<Integer> typeIds, Map<Integer, Color> colors) {
        Color found = null;
        int best = Integer.MAX_VALUE;
        boolean phone = false;
        for (int typeId : typeIds) {
            if (typeId == BlackoutOverlayTypes.STREET_PHONE
                    || typeId == BlackoutOverlayTypes.ROTARY_PHONE_RED
                    || typeId == BlackoutOverlayTypes.HORN) {
                phone = true;
            }
            if (typeId < BlackoutOverlayTypes.CUSTOM_OVERLAY_MIN_TYPE_ID) {
                continue;
            }
            Color mapped = colors.get(typeId);
            if (mapped != null && typeId < best) {
                best = typeId;
                found = mapped;
            }
        }
        if (found != null) {
            return found;
        }
        if (phone) {
            return PhoneOverlayRenderer.PHONE_OVERLAY_COLOR;
        }
        return FALLBACK_COLOR;
    }
}
