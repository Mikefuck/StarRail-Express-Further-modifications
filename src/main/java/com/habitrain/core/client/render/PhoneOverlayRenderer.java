package com.habitrain.core.client.render;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.gui.BlackoutVoteState;
import com.habitrain.core.client.mixin.CustomTaskBlockRendererMixin;
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.core.BlockPos;

import java.awt.Color;
import java.util.Set;

/**
 * 常量透视方块渲染。
 *
 * 渲染停电模式下的"电话方块"(yuushya:street_phone)等高亮边框。
 * 这些方块不由 DLC 任务系统注册，而是通过 MapScannerMixin 扫描并广播到客户端。
 *
 * 渲染条件：
 * - 游戏正在运行（SRE 非大厅阶段）
 * - 停电模式已激活
 */
@Environment(EnvType.CLIENT)
public final class PhoneOverlayRenderer {

    /** 电话方块描边颜色：金色 */
    private static final Color PHONE_OVERLAY_COLOR = new Color(0xFFFFD700, true);
    /** 电话方块描边线宽 */
    private static final float PHONE_OVERLAY_LINE_WIDTH = 5.0f;

    private PhoneOverlayRenderer() {}

    /**
     * 渲染所有街机电话方块的常量透视边框。
     *
     * @param context 渲染上下文
     */
    public static void render(WorldRenderContext context) {
        var level = context.world();
        if (level == null) return;

        // 只在 game running 且停电模式激活时渲染
        if (!GameRunningCache.isGameRunning()) return;
        if (!BlackoutVoteState.isBlackoutModeActive()) return;

        int rendered = 0;
        for (BlockPos pos : CustomTaskBlockCache.keySet()) {
            Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
            if (typeIds == null || !typeIds.contains(BlackoutOverlayTypes.STREET_PHONE)) {
                continue;
            }
            CustomTaskBlockRendererMixin.renderCustomOverlay(
                    context, pos, PHONE_OVERLAY_COLOR, PHONE_OVERLAY_LINE_WIDTH);
            rendered++;
        }
        if (rendered > 0) {
            HabiTrainCore.LOGGER.debug("[PhoneOverlayRenderer] rendered {} constant overlay blocks", rendered);
        }
    }
}
