package com.habitrain.core.scene.client;

import com.habitrain.core.scene.item.HabiAdminItems;
import com.habitrain.core.scene.model.SceneBounds;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * 场景配置器 HUD 状态渲染器（手持配置器道具时在屏幕左上角显示选区范围、Section 数、方块数与快捷键操作指引）。
 */
public final class SceneToolHud implements HudRenderCallback {
    private static final SceneToolHud INSTANCE = new SceneToolHud();

    public static SceneToolHud getInstance() {
        return INSTANCE;
    }

    private String currentMapKey = "";
    private SceneBounds currentBounds = SceneBounds.EMPTY;
    private long currentBlockCount = 0L;

    private SceneToolHud() {}

    public static void init() {
        HudRenderCallback.EVENT.register(INSTANCE);
    }

    public synchronized void updateState(String mapKey, SceneBounds bounds, long blockCount) {
        this.currentMapKey = mapKey != null ? mapKey : "";
        this.currentBounds = bounds != null ? bounds : SceneBounds.EMPTY;
        this.currentBlockCount = blockCount;
    }

    public synchronized void updateMapKey(String mapKey) {
        this.currentMapKey = mapKey != null ? mapKey : "";
    }

    public synchronized String getCurrentMapKey() {
        return currentMapKey;
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
        int lineHeight = 10;

        SceneOriginPlacementController placement = SceneOriginPlacementController.getInstance();
        if (placement.isActive()) {
            BlockPos target = placement.getTargetBlock();
            guiGraphics.fill(x - 4, y - 4, x + 226, y + 46, 0xA0000000);
            guiGraphics.drawString(font, "§6[显示原点自动摆放]", x, y, 0xFFFFFF, false);
            y += lineHeight;
            guiGraphics.drawString(font, "§7预览尺寸: §6" + placement.sizeX() + "×"
                    + placement.sizeY() + "×" + placement.sizeZ(), x, y, 0xFFFFFF, false);
            y += lineHeight;
            String targetText = target == null ? "§c未瞄准方块" : "§a(" + target.getX() + ", "
                    + target.getY() + ", " + target.getZ() + ")";
            guiGraphics.drawString(font, "§7候选原点: " + targetText, x, y, 0xFFFFFF, false);
            y += lineHeight;
            guiGraphics.drawString(font, "§6[对准方块并按 Shift+右键确认]", x, y, 0xFFFFFF, false);
            return;
        }

        // 背景半透明黑板
        guiGraphics.fill(x - 4, y - 4, x + 190, y + 54, 0x90000000);

        guiGraphics.drawString(font, "§6[移动场景配置器]", x, y, 0xFFFFFF, false);
        y += lineHeight;

        String mapDisplay = currentMapKey.isBlank() ? "§7(未命名地图)" : "§b" + currentMapKey;
        guiGraphics.drawString(font, "§7地图: " + mapDisplay, x, y, 0xFFFFFF, false);
        y += lineHeight;

        if (currentBounds != null && !currentBounds.isEmpty()) {
            guiGraphics.drawString(font, "§7选区: §a" + currentBounds.sizeX() + "×" + currentBounds.sizeY() + "×" + currentBounds.sizeZ()
                    + " §7(" + currentBounds.totalSections() + " sections)", x, y, 0xFFFFFF, false);
            y += lineHeight;
            guiGraphics.drawString(font, "§7方块估算: §e" + currentBlockCount + " blocks", x, y, 0xFFFFFF, false);
            y += lineHeight;
        } else {
            guiGraphics.drawString(font, "§7选区: §c未选定 (Shift+右键方块选点)", x, y, 0xFFFFFF, false);
            y += lineHeight;
        }

        guiGraphics.drawString(font, "§8[右键打开设置 | Shift+右键空气清除]", x, y, 0xAAAAAA, false);
    }
}
