package com.habitrain.core.scene.client;

import net.minecraft.core.BlockPos;

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
        SceneOriginPlacementController placement = SceneOriginPlacementController.getInstance();
        SceneBounds originPreview = placement.getPreviewBounds();
        if ((selection == null || selection.isEmpty()) && originPreview.isEmpty() && !placement.isActive()) return;

        Camera camera = context.camera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = context.matrixStack();
        VertexConsumer lineConsumer = context.consumers().getBuffer(RenderType.lines());

        if (selection != null && !selection.isEmpty()) {
            renderBounds(poseStack, lineConsumer, camPos, selection, 0.2f, 0.8f, 1.0f);
        }
        if (placement.isActive()) {
            if (placement.getTargetType() == SceneOriginPlacementController.TargetType.PLACE_ORIGIN) {
                if (!originPreview.isEmpty()) {
                    // 原点摆放预览使用琥珀橙，与源选区的青色明显区分。
                    renderBounds(poseStack, lineConsumer, camPos, originPreview, 1.0f, 0.45f, 0.08f);
                }
            } else {
                // 绕转中心选择：青蓝色高亮候选方块
                BlockPos target = placement.getTargetBlock();
                if (target != null) {
                    SceneBounds centerBox = new SceneBounds(
                            target.getX(), target.getY(), target.getZ(),
                            target.getX() + 1, target.getY() + 1, target.getZ() + 1
                    );
                    renderBounds(poseStack, lineConsumer, camPos, centerBox, 0.2f, 0.9f, 1.0f);

                    double cx = target.getX() + 0.5;
                    double cy = target.getY() + 0.5;
                    double cz = target.getZ() + 0.5;
                    double[] origin = placement.getDisplayOrigin();
                    renderLine(poseStack, lineConsumer, camPos, cx, cy, cz, origin[0], origin[1], origin[2], 0.2f, 0.9f, 1.0f);
                    renderOrbitCircle(poseStack, lineConsumer, camPos, cx, cy, cz, origin, placement.getOrbitAxis(), 0.2f, 0.9f, 1.0f);
                }
            }
        }
    }

    private static void renderLine(PoseStack poseStack, VertexConsumer lineConsumer, Vec3 camPos,
                                   double x1, double y1, double z1, double x2, double y2, double z2,
                                   float red, float green, float blue) {
        org.joml.Matrix4f pose = poseStack.last().pose();
        float nx = (float) (x2 - x1);
        float ny = (float) (y2 - y1);
        float nz = (float) (z2 - z1);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-4f) { nx /= len; ny /= len; nz /= len; } else { ny = 1.0f; }
        lineConsumer.addVertex(pose, (float) (x1 - camPos.x), (float) (y1 - camPos.y), (float) (z1 - camPos.z))
                .setColor(red, green, blue, 1.0f).setNormal(poseStack.last(), nx, ny, nz);
        lineConsumer.addVertex(pose, (float) (x2 - camPos.x), (float) (y2 - camPos.y), (float) (z2 - camPos.z))
                .setColor(red, green, blue, 1.0f).setNormal(poseStack.last(), nx, ny, nz);
    }

    private static void renderOrbitCircle(PoseStack poseStack, VertexConsumer lineConsumer, Vec3 camPos,
                                          double cx, double cy, double cz, double[] origin,
                                          com.habitrain.core.scene.model.SceneOrbitAxis axis,
                                          float red, float green, float blue) {
        double[] a = com.habitrain.core.scene.model.SceneOrbitMath.unitAxis(axis);
        double dx = origin[0] - cx;
        double dy = origin[1] - cy;
        double dz = origin[2] - cz;
        double h = dx * a[0] + dy * a[1] + dz * a[2];
        double vx = dx - a[0] * h;
        double vy = dy - a[1] * h;
        double vz = dz - a[2] * h;
        double radius = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (radius < 0.2) return;

        int segments = 48;
        for (int i = 0; i < segments; i++) {
            double angle1 = (i * 360.0) / segments;
            double angle2 = ((i + 1) * 360.0) / segments;
            double[] b1 = com.habitrain.core.scene.model.SceneOrbitMath.unitRadiusVector(axis, angle1);
            double[] b2 = com.habitrain.core.scene.model.SceneOrbitMath.unitRadiusVector(axis, angle2);
            double px1 = cx + a[0] * h + b1[0] * radius;
            double py1 = cy + a[1] * h + b1[1] * radius;
            double pz1 = cz + a[2] * h + b1[2] * radius;
            double px2 = cx + a[0] * h + b2[0] * radius;
            double py2 = cy + a[1] * h + b2[1] * radius;
            double pz2 = cz + a[2] * h + b2[2] * radius;
            renderLine(poseStack, lineConsumer, camPos, px1, py1, pz1, px2, py2, pz2, red, green, blue);
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
