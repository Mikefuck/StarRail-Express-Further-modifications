package com.habitrain.core.client.gui;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.game.blackout.BlackoutExclusiveTasks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 停电专属任务右上角当前任务名 HUD。
 * <p>
 * 左上角 SRE 任务栏在专属任务活跃期间保持为空；此处单独显示当前电话/强制任务名称。
 */
@Environment(EnvType.CLIENT)
public final class BlackoutActiveTaskHud {

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int PADDING_RIGHT = 8;
    private static final int TEXT_Y = 8;

    private BlackoutActiveTaskHud() {}

    public static void render(GuiGraphics g) {
        if (!ClientBlackoutState.isBlackoutModeActive()) return;
        if (!ActiveTaskCache.hasActiveTask()) return;

        String fullId = ActiveTaskCache.getActiveTaskFullId();
        if (!BlackoutExclusiveTasks.isExclusive(fullId)) return;

        String displayName = resolveDisplayName(fullId);
        if (displayName == null || displayName.isBlank()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Font font = mc.font;
        int width = mc.getWindow().getGuiScaledWidth();
        int textWidth = font.width(displayName);
        int x = width - PADDING_RIGHT - textWidth;
        g.drawString(font, displayName, x, TEXT_Y, COLOR_TEXT, false);
    }

    private static String resolveDisplayName(String fullId) {
        TaskDefinition def = TaskRegistry.get(fullId);
        if (def != null) {
            String name = def.getDisplayName();
            if (name != null && !name.isBlank()) return name;
        }
        // fallback: strip namespace
        int colon = fullId.indexOf(':');
        return colon >= 0 ? fullId.substring(colon + 1) : fullId;
    }
}
