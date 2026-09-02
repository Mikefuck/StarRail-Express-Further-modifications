package com.habitrain.core.scene.client;

import com.habitrain.core.scene.item.HabiAdminItems;
import com.habitrain.core.scene.model.SceneBounds;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 场景配置器选区世界高亮渲染器（玩家手持配置器道具时在世界中渲染 A/B 点高亮与选区发光边框与半透明填充）。
 */
public final class SceneToolSelectionRenderer {
    private static final SceneToolSelectionRenderer INSTANCE = new SceneToolSelectionRenderer();

    public static SceneToolSelectionRenderer getInstance() {
        return INSTANCE;
    }

    private SceneBounds currentSelection = SceneBounds.EMPTY;

    private SceneToolSelectionRenderer() {}

    public synchronized void updateSelection(SceneBounds bounds) {
        this.currentSelection = bounds != null ? bounds : SceneBounds.EMPTY;
    }

    public synchronized SceneBounds getSelection() {
        return currentSelection;
    }

    public synchronized void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        // 仅手持配置器道具时渲染
        boolean holdingTool = player.getMainHandItem().is(HabiAdminItems.SCENE_CONFIGURATOR)
                || player.getOffhandItem().is(HabiAdminItems.SCENE_CONFIGURATOR);
        if (!holdingTool) return;

        SceneBounds selection = currentSelection;
        SceneBounds originPreview = SceneOriginPlacementController.getInstance().getPreviewBounds();
        if ((selection == null || selection.isEmpty()) && originPreview.isEmpty()) return;

        Camera camera = context.camera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = context.matrixStack();
        VertexConsumer lineConsumer = context.consumers().getBuffer(RenderType.lines());

        if (selection != null && !selection.isEmpty()) {
            renderBounds(poseStack, lineConsumer, camPos, selection, 0.2f, 0.8f, 1.0f);
        }
        if (!originPreview.isEmpty()) {
            // 原点摆放预览使用琥珀橙，与源选区的青色明显区分。
            renderBounds(poseStack, lineConsumer, camPos, originPreview, 1.0f, 0.45f, 0.08f);
        }
    }

    private static void renderBounds(PoseStack poseStack, VertexConsumer lineConsumer, Vec3 camPos,
                                     SceneBounds bounds, float red, float green, float blue) {
        AABB aabb = new AABB(
                bounds.minX() - camPos.x,
                bounds.minY() - camPos.y,
                bounds.minZ() - camPos.z,
                bounds.maxX() - camPos.x,
                bounds.maxY() - camPos.y,
                bounds.maxZ() - camPos.z
        );
        LevelRenderer.renderLineBox(poseStack, lineConsumer, aabb, red, green, blue, 1.0f);
    }
}
