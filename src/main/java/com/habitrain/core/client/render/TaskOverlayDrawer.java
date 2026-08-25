package com.habitrain.core.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.habitrain.core.client.mixin.FrustumAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Non-mixin drawer for custom task block ESP outlines.
 *
 * <p>Must live outside any {@code @Mixin} class: Mixin rejects non-private static methods
 * on mixin classes ({@code InvalidMixinException}), which previously killed all DLC ESP.
 *
 * <p>Wall-through notes (1.21 / AFTER_TRANSLUCENT):
 * <ul>
 *   <li>{@link RenderStateShard#NO_DEPTH_TEST} alone is not enough if the batch is flushed later
 *       with a different depth state, or if {@code ITEM_ENTITY_TARGET} is composited with depth.</li>
 *   <li>Use {@link RenderStateShard#MAIN_TARGET} + immediate {@code endBatch(type)} so the
 *       NO_DEPTH_TEST state is applied when vertices are actually submitted.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class TaskOverlayDrawer {

    private static final Map<Float, RenderType> RENDER_TYPE_CACHE = new HashMap<>();

    /** Default SRE-style line width (matches {@code ALWAYS_VISIBLE_THICK_LINES}). */
    public static final float DEFAULT_LINE_WIDTH = 4.0f;
    public static final double SURVIVAL_OVERLAY_DISTANCE = 32.0;
    public static final double SPECTATOR_OVERLAY_DISTANCE = 24.0;
    public static final double SURVIVAL_OVERLAY_DISTANCE_SQ = SURVIVAL_OVERLAY_DISTANCE * SURVIVAL_OVERLAY_DISTANCE;
    public static final double SPECTATOR_OVERLAY_DISTANCE_SQ = SPECTATOR_OVERLAY_DISTANCE * SPECTATOR_OVERLAY_DISTANCE;
    /**
     * Spectator sparse custom points (cats, phones, backpacks): frustum only,
     * matching vanilla SRE task ESP which has no distance gate.
     */
    public static final double SPECTATOR_SPARSE_OVERLAY_DISTANCE_SQ = Double.POSITIVE_INFINITY;

    private TaskOverlayDrawer() {}

    /**
     * Memoized see-through line RenderType. Safe to share across SRE redirect + Habi drawer.
     */
    public static RenderType throughWallLines(float lineWidth) {
        float w = lineWidth > 0f ? lineWidth : DEFAULT_LINE_WIDTH;
        return RENDER_TYPE_CACHE.computeIfAbsent(w, TaskOverlayDrawer::createThroughWallType);
    }

    private static RenderType createThroughWallType(float w) {
        return RenderType.create(
                "habitrain_task_overlay_xray_" + w,
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES, 256, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(w)))
                        // MAIN_TARGET: drawn into the main color buffer so NO_DEPTH_TEST actually
                        // covers terrain. ITEM_ENTITY_TARGET can be re-composited with depth on
                        // some pipelines / shader packs, which looks like "outline but no xray".
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .createCompositeState(false)
        );
    }

    /**
     * Draw a see-through line box for a block at {@code blockPos}.
     * Color alpha is taken from the {@link Color} itself.
     */
    public static void renderOverlay(
            WorldRenderContext context, BlockPos blockPos, Color color, float lineWidth) {
        if (context == null || blockPos == null || color == null) return;

        Minecraft client = Minecraft.getInstance();
        Level world = client != null ? client.level : null;
        if (world == null) return;

        PoseStack matrices = context.matrixStack();
        if (matrices == null) return;

        BlockState state = world.getBlockState(blockPos);
        AABB localAABB = getCombinedAABB(world, blockPos, state);
        RenderType type = throughWallLines(lineWidth);

        // Independent BufferSource + immediate endBatch: do not wait for the world renderer's
        // deferred flush (which can apply a different depth state and kill wall-through).
        MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();
        VertexConsumer vertexConsumer = bufferSource.getBuffer(type);

        matrices.pushPose();
        Vec3 cameraPos = context.camera().getPosition();
        matrices.translate(
                blockPos.getX() - cameraPos.x,
                blockPos.getY() - cameraPos.y,
                blockPos.getZ() - cameraPos.z);

        float red = color.getRed() / 255f;
        float green = color.getGreen() / 255f;
        float blue = color.getBlue() / 255f;
        float alpha = color.getAlpha() / 255f;

        LevelRenderer.renderLineBox(matrices, vertexConsumer, localAABB, red, green, blue, alpha);
        matrices.popPose();
    }

    /** Flush every overlay RenderType once per world-render pass. */
    public static void endOverlayPass() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        MultiBufferSource.BufferSource source = client.renderBuffers().bufferSource();
        for (RenderType type : RENDER_TYPE_CACHE.values()) {
            source.endBatch(type);
        }
    }

    public static boolean isInOverlayRange(WorldRenderContext context, BlockPos pos) {
        return isInOverlayRange(context, pos, SURVIVAL_OVERLAY_DISTANCE_SQ);
    }

    public static boolean isInOverlayRange(WorldRenderContext context, BlockPos pos, double maxDistanceSq) {
        if (context == null || pos == null || context.camera() == null) return false;
        Vec3 cameraPos = context.camera().getPosition();
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;
        double dx = (minX + 0.5) - cameraPos.x;
        double dy = (minY + 0.5) - cameraPos.y;
        double dz = (minZ + 0.5) - cameraPos.z;
        if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
            return false;
        }
        Frustum frustum = context.frustum();
        // 1.21 AABB fields are final (no AABB.set); invoke cubeInFrustum with primitives.
        if (frustum != null
                && !((FrustumAccessor) frustum).habitrain$cubeInFrustum(minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        return true;
    }

    /**
     * Local-space AABB relative to {@code blockPos}, matching SRE multi-block handling
     * for doors / beds where possible.
     */
    public static AABB getCombinedAABB(Level world, BlockPos blockPos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(world, blockPos);
        if (shape.isEmpty()) {
            shape = state.getShape(world, blockPos);
        }
        if (shape.isEmpty()) {
            return new AABB(0, 0, 0, 1, 1, 1);
        }

        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            BlockPos otherPos = half == DoubleBlockHalf.LOWER ? blockPos.above() : blockPos.below();
            VoxelShape otherShape = world.getBlockState(otherPos).getCollisionShape(world, otherPos);
            AABB a = shape.bounds();
            if (otherShape.isEmpty()) {
                return half == DoubleBlockHalf.LOWER
                        ? a.expandTowards(0, 1, 0)
                        : a.expandTowards(0, -1, 0);
            }
            AABB b = otherShape.bounds().move(0, half == DoubleBlockHalf.LOWER ? 1 : -1, 0);
            return new AABB(
                    Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                    Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
        }

        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            BedPart part = state.getValue(BlockStateProperties.BED_PART);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Direction extend = part == BedPart.FOOT ? facing : facing.getOpposite();
            BlockPos otherPos = blockPos.relative(extend);
            VoxelShape otherShape = world.getBlockState(otherPos).getCollisionShape(world, otherPos);
            AABB a = shape.bounds();
            if (otherShape.isEmpty()) {
                return a.expandTowards(extend.getStepX(), 0, extend.getStepZ());
            }
            AABB b = otherShape.bounds().move(extend.getStepX(), 0, extend.getStepZ());
            return new AABB(
                    Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                    Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
        }

        return shape.bounds();
    }
}
