package com.habitrain.core.client.render;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.core.BlockPos;

import java.awt.Color;
import java.util.Set;

/**
 * 常量透视方块渲染（电话等）。
 *
 * <p>不依赖 mixin 类上的 public 方法；绘制走 {@link TaskOverlayDrawer}。
 */
@Environment(EnvType.CLIENT)
public final class PhoneOverlayRenderer {

    public static final Color PHONE_OVERLAY_COLOR = new Color(0xFFFFD700, true);
    public static final float PHONE_OVERLAY_LINE_WIDTH = 5.0f;

    private PhoneOverlayRenderer() {}

    public static void render(WorldRenderContext context) {
        var level = context.world();
        if (level == null) return;

        if (!GameRunningCache.isGameRunning()) return;
        if (!com.habitrain.core.client.gui.ClientBlackoutState.isBlackoutModeActive()) return;

        int rendered = 0;
        for (BlockPos pos : CustomTaskBlockCache.keySet()) {
            Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
            if (typeIds == null) continue;
            // 常驻透视：street_phone（警察聘请）+ rotary_phone_red（任务商店）
            if (typeIds.contains(BlackoutOverlayTypes.STREET_PHONE)
                    || typeIds.contains(BlackoutOverlayTypes.ROTARY_PHONE_RED)) {
                TaskOverlayDrawer.renderOverlay(
                        context, pos, PHONE_OVERLAY_COLOR, PHONE_OVERLAY_LINE_WIDTH);
                rendered++;
            }
        }
        if (rendered > 0) {
            HabiTrainCore.LOGGER.debug("[PhoneOverlayRenderer] rendered {} constant overlay blocks", rendered);
        }
    }
}
