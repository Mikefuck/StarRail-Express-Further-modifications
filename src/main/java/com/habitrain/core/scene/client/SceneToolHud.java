package com.habitrain.core.scene.client;

import com.habitrain.core.scene.item.HabiAdminItems;
import com.habitrain.core.scene.model.SceneBounds;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 场景配置器 HUD 状态渲染器（手持配置器道具时在屏幕左上角显示选区范围、Section 数、方块数与快捷键操作指引）。
 */
public final class SceneToolHud implements HudRenderCallback {
    private static final SceneToolHud INSTANCE = new SceneToolHud();

    public static SceneToolHud getInstance() {
        return INSTANCE;
    }

    private String currentMapKey = "";
    private String currentBackgroundId = "";
    private String currentBackgroundName = "";
    private SceneBounds currentBounds = SceneBounds.EMPTY;
    private long currentBlockCount = 0L;

    private SceneToolHud() {}

    public static void init() {
        HudRenderCallback.EVENT.register(INSTANCE);
    }

    public synchronized void updateState(String mapKey, SceneBounds bounds, long blockCount) {
        if (mapKey != null && !mapKey.isBlank() && !mapKey.equals(this.currentMapKey)) {
            this.currentMapKey = mapKey;
            this.currentBackgroundId = "";
            this.currentBackgroundName = "";
        } else if (mapKey != null) {
            this.currentMapKey = mapKey;
        }
        this.currentBounds = bounds != null ? bounds : SceneBounds.EMPTY;
        this.currentBlockCount = blockCount;
    }

    public synchronized void updateMapKey(String mapKey) {
        if (mapKey != null && !mapKey.isBlank() && !mapKey.equals(this.currentMapKey)) {
            this.currentBackgroundId = "";
            this.currentBackgroundName = "";
        }
        this.currentMapKey = mapKey != null ? mapKey : "";
    }

    public synchronized void updateSelectionTarget(String mapKey, String backgroundId, String backgroundName) {
        this.currentMapKey = mapKey != null ? mapKey : "";
        this.currentBackgroundId = backgroundId != null ? backgroundId : "";
        this.currentBackgroundName = backgroundName != null ? backgroundName : "";
    }

    public synchronized void updateBackground(String backgroundId, String backgroundName) {
        this.currentBackgroundId = backgroundId != null ? backgroundId : "";
        this.currentBackgroundName = backgroundName != null ? backgroundName : "";
    }

    public synchronized String getCurrentMapKey() {
        return currentMapKey;
    }

    public synchronized String getCurrentBackgroundId() {
        return currentBackgroundId;
    }

    @Override
    public void onHudRender(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui) return;

        boolean holdingTool = player.getMainHandItem().is(HabiAdminItems.SCENE_CONFIGURATOR)
                || player.getOffhandItem().is(HabiAdminItems.SCENE_CONFIGURATOR);
        if (!holdingTool) return;

        Font font = mc.font;
        int x = 8;
        int y = 8;
        List<Component> lines = new ArrayList<>();

        SceneOriginPlacementController placement = SceneOriginPlacementController.getInstance();
        if (placement.isActive()) {
            BlockPos target = placement.getTargetBlock();
            if (placement.getTargetType() == SceneOriginPlacementController.TargetType.ORBIT_CENTER) {
                lines.add(tr("hud.habitrain_core.scene_tool.orbit_title", ChatFormatting.AQUA));
                double[] origin = placement.getDisplayOrigin();
                Component targetText;
                if (target == null) {
                    targetText = tr("hud.habitrain_core.scene_tool.target_none", ChatFormatting.RED);
                } else {
                    double cx = target.getX() + 0.5;
                    double cy = target.getY() + 0.5;
                    double cz = target.getZ() + 0.5;
                    double dx = origin[0] - cx;
                    double dy = origin[1] - cy;
                    double dz = origin[2] - cz;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    targetText = Component.translatable("hud.habitrain_core.scene_tool.center_value",
                            formatPosition(cx, cy, cz), String.format(Locale.ROOT, "%.1f", dist));
                }
                lines.add(tr("hud.habitrain_core.scene_tool.candidate_center", ChatFormatting.GRAY, targetText));
                lines.add(tr("hud.habitrain_core.scene_tool.model_origin", ChatFormatting.GRAY,
                        formatPosition(origin[0], origin[1], origin[2])));
                lines.add(tr("hud.habitrain_core.scene_tool.confirm_center", ChatFormatting.AQUA));
            } else {
                lines.add(tr("hud.habitrain_core.scene_tool.origin_title", ChatFormatting.GOLD));
                lines.add(tr("hud.habitrain_core.scene_tool.preview_size", ChatFormatting.GRAY,
                        placement.sizeX(), placement.sizeY(), placement.sizeZ()));
                Component targetText = target == null
                        ? tr("hud.habitrain_core.scene_tool.target_none", ChatFormatting.RED)
                        : Component.literal(formatPosition(target.getX(), target.getY(), target.getZ()))
                                .withStyle(ChatFormatting.GREEN);
                lines.add(tr("hud.habitrain_core.scene_tool.candidate_origin", ChatFormatting.GRAY, targetText));
                lines.add(tr("hud.habitrain_core.scene_tool.confirm_origin", ChatFormatting.GOLD));
            }
            drawPanel(guiGraphics, font, x, y, lines, 0xA0000000);
            return;
        }

        lines.add(tr("hud.habitrain_core.scene_tool.title", ChatFormatting.GOLD));
        Component mapDisplay = currentMapKey.isBlank()
                ? tr("hud.habitrain_core.scene_tool.unnamed_map", ChatFormatting.GRAY)
                : Component.literal(currentMapKey).withStyle(ChatFormatting.AQUA);
        lines.add(tr("hud.habitrain_core.scene_tool.map", ChatFormatting.GRAY, mapDisplay));

        if (!currentBackgroundId.isBlank()) {
            Component bgDisplay = currentBackgroundName.isBlank() || currentBackgroundName.equals(currentBackgroundId)
                    ? Component.literal(currentBackgroundId).withStyle(ChatFormatting.LIGHT_PURPLE)
                    : Component.literal(currentBackgroundName + " (" + currentBackgroundId + ")").withStyle(ChatFormatting.LIGHT_PURPLE);
            lines.add(tr("hud.habitrain_core.scene_tool.background", ChatFormatting.GRAY, bgDisplay));
        }

        if (currentBounds != null && !currentBounds.isEmpty()) {
            lines.add(tr("hud.habitrain_core.scene_tool.selection", ChatFormatting.GRAY,
                    currentBounds.sizeX(), currentBounds.sizeY(), currentBounds.sizeZ(),
                    currentBounds.totalSections()));
            lines.add(tr("hud.habitrain_core.scene_tool.block_estimate", ChatFormatting.GRAY,
                    currentBlockCount));
        } else {
            lines.add(tr("hud.habitrain_core.scene_tool.selection_unset", ChatFormatting.RED));
        }
        lines.add(tr("hud.habitrain_core.scene_tool.help", ChatFormatting.DARK_GRAY));
        drawPanel(guiGraphics, font, x, y, lines, 0x90000000);
    }

    private static MutableComponent tr(String key, ChatFormatting color, Object... args) {
        return Component.translatable(key, args).withStyle(color);
    }

    private static String formatPosition(double x, double y, double z) {
        return "(" + String.format(Locale.ROOT, "%.1f", x) + ", "
                + String.format(Locale.ROOT, "%.1f", y) + ", "
                + String.format(Locale.ROOT, "%.1f", z) + ")";
    }

    private static String formatPosition(int x, int y, int z) {
        return "(" + x + ", " + y + ", " + z + ")";
    }

    private static void drawPanel(GuiGraphics graphics, Font font, int x, int y,
                                  List<Component> lines, int backgroundColor) {
        int wrapWidth = Math.max(96, Math.min(320, graphics.guiWidth() - x - 8));
        List<FormattedCharSequence> rendered = new ArrayList<>();
        for (Component line : lines) {
            List<FormattedCharSequence> split = font.split(line, wrapWidth);
            if (split.isEmpty()) rendered.add(line.getVisualOrderText());
            else rendered.addAll(split);
        }
        int textWidth = rendered.stream().mapToInt(font::width).max().orElse(96);
        int lineHeight = font.lineHeight + 1;
        graphics.fill(x - 4, y - 4, x + textWidth + 4,
                y + rendered.size() * lineHeight + 3, backgroundColor);
        int drawY = y;
        for (FormattedCharSequence line : rendered) {
            graphics.drawString(font, line, x, drawY, 0xFFFFFF, false);
            drawY += lineHeight;
        }
    }
}
