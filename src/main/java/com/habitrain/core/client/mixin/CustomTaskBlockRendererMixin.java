package com.habitrain.core.client.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.client.render.BlockStageScanner;
import com.habitrain.core.client.render.GameRunningCache;
import com.habitrain.core.client.render.PhoneOverlayRenderer;
import com.habitrain.core.client.render.ViewModeDispatcher;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.client.gui.BlackoutVoteState;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.content.block.api.TaskInstinctShowableInterface;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.client.TaskBlockOverlayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Mixin - 注入 {@link TaskBlockOverlayRenderer#render(WorldRenderContext)} 方法
 *
 * 在渲染循环的末尾添加自定义DLC任务方块的高亮边框。
 *
 * 设计要点：
 * - 旁观/创造模式：渲染所有自定义任务方块（类型 ≥12），无需活跃任务
 *   → 与原始 render() 中 shouldDisplay[1..11] = true 的行为一致
 * - 生存模式：仅渲染玩家当前活跃的自定义任务匹配的方块
 * - 颜色从 {@link TaskDefinition#getInstinctColor()} 获取，
 *   如有 ModMenu 配置则使用配置颜色（与 InstinctColorMixin 逻辑一致）
 * - 透明度使用 Color 自身的 alpha 通道（不再硬编码 0.2f）
 * - 描边粗细可从配置读取（SRE 默认 4.0）
 *
 * <p>复杂逻辑已拆分到 {@code client/render/} 包下的专注类中：
 * <ul>
 *   <li>{@link GameRunningCache}</li>
 *   <li>{@link com.habitrain.core.client.render.TypeColorMapper}</li>
 *   <li>{@link PhoneOverlayRenderer}</li>
 *   <li>{@link BlockStageScanner}</li>
 *   <li>{@link ViewModeDispatcher}</li>
 * </ul>
 */
    @Environment(EnvType.CLIENT)
    @Mixin(TaskBlockOverlayRenderer.class)
    public class CustomTaskBlockRendererMixin {

    // ====== @Shadow: 访问 TaskBlockOverlayRenderer 的 private static 方法 ======

    @Shadow
    private static AABB getCombinedAABB(Level world, BlockPos blockPos, BlockState state) {
        throw new AssertionError("Shadowed method not implemented");
    }

    // ====== 可配置粗细的 RenderType 缓存 ======

    /**
     * 按线宽缓存的 RenderType，避免每帧重复创建
     */
    private static final Map<Float, RenderType> RENDER_TYPE_CACHE = new HashMap<>();

    /**
     * 获取指定线宽的透视描边 RenderType
     */
    private static RenderType getRenderType(float lineWidth) {
        return RENDER_TYPE_CACHE.computeIfAbsent(lineWidth, w -> RenderType.create(
                "custom_task_overlay_" + w,
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES, 256, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(w)))
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .createCompositeState(false)
        ));
    }

    /**
     * 使用可配置粗细和 Color 自身 alpha 的自定义渲染。
     *
     * <p>被 {@link ViewModeDispatcher} 和 {@link PhoneOverlayRenderer} 调用。
     */
    public static void renderCustomOverlay(
            WorldRenderContext context, BlockPos blockPos, Color color, float lineWidth) {
        float alpha = color.getAlpha() / 255f;
        Minecraft client = Minecraft.getInstance();
        var world = client.level;
        if (world == null) return;

        BlockState state = world.getBlockState(blockPos);
        AABB localAABB = getCombinedAABB(world, blockPos, state);
        MultiBufferSource vertexConsumers = context.consumers();
        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(getRenderType(lineWidth));

        PoseStack matrices = context.matrixStack();
        matrices.pushPose();

        Vec3 cameraPos = context.camera().getPosition();
        matrices.translate(
                blockPos.getX() - cameraPos.x,
                blockPos.getY() - cameraPos.y,
                blockPos.getZ() - cameraPos.z);

        float red = color.getRed() / 255f;
        float green = color.getGreen() / 255f;
        float blue = color.getBlue() / 255f;

        LevelRenderer.renderLineBox(matrices, vertexConsumer, localAABB, red, green, blue, alpha);

        matrices.popPose();
    }

    // ====== @Inject(TAIL) - 主渲染入口（纯委托）======

    /**
     * 在原始渲染末尾加入自定义任务的方块高亮。
     * 所有复杂逻辑已委托到 {@code client/render/} 包下的专注类。
     */
    @Inject(
            method = "render",
            at = @At("TAIL"),
            remap = false
    )
    private static void habitrain$renderCustomTaskBlocks(WorldRenderContext renderContext, CallbackInfo ci) {
        var instance = Minecraft.getInstance();
        if (instance == null || instance.player == null || instance.level == null) return;

        if (CustomTaskBlockCache.isEmpty()) return;

        // 大厅阶段（无活跃游戏）→ 不渲染任何自定义任务方块
        if (!GameRunningCache.isGameRunning()) return;

        // ===== 旁观/创造模式 =====
        if (SREClient.isPlayerSpectatingOrCreative()) {
            ViewModeDispatcher.renderAll(renderContext);
            PhoneOverlayRenderer.render(renderContext);
            return;
        }

        // ===== 生存模式：需要活跃的自定义任务 =====
        TaskManager mgr = TaskManager.getInstance();
        var customTask = mgr.getActiveTask(instance.player.getUUID());

        // 确定任务颜色和 type
        int blockTypeId;
        Color taskColor;
        float lineWidth = 4.0f;

        if (customTask != null) {
            blockTypeId = customTask.getDefinition().getBlockTypeId();
            if (blockTypeId <= 12) {
                PhoneOverlayRenderer.render(renderContext);
                return;
            }
            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(customTask.getFullId());
            if (cfg != null) {
                taskColor = new Color(cfg.getColor(), true);
                lineWidth = cfg.outlineWidth;
            } else {
                taskColor = new Color(customTask.getDefinition().getInstinctColorRGB(), true);
            }
        } else {
            String activeTaskId = ActiveTaskCache.getActiveTaskFullId();
            if (activeTaskId == null) {
                PhoneOverlayRenderer.render(renderContext);
                return;
            }
            blockTypeId = ActiveTaskCache.getBlockTypeId();
            if (blockTypeId < 12) {
                PhoneOverlayRenderer.render(renderContext);
                return;
            }
            if (blockTypeId == 12) {
                PhoneOverlayRenderer.render(renderContext);
                return;
            }
            taskColor = ActiveTaskCache.getColor();
            lineWidth = ActiveTaskCache.getOutlineWidth();
        }

        String taskName = customTask != null ? customTask.getFullId() : ActiveTaskCache.getActiveTaskFullId();

        boolean isAddCoalTask = "habitrain_core:add_coal".equals(taskName);
        boolean hasCoal = isAddCoalTask && BlockStageScanner.hasPlayerCoal(instance.player);

        boolean isFurnaceExplosionTask = "habitrain_core:furnace_explosion".equals(taskName);
        boolean hasTorch = isFurnaceExplosionTask && BlockStageScanner.hasPlayerRedstoneTorch(instance.player);

        // 检查是否需要渲染电话方块（停电模式下常量透视）— 不依赖 active task
        boolean shouldRenderPhone = BlackoutVoteState.isBlackoutModeActive();

        int renderedCount = 0;
        var level = renderContext.world();
        for (BlockPos pos : CustomTaskBlockCache.keySet()) {
            Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
            if (typeIds == null) continue;

            // 先检查电话方块（常量透视）— S9-004：合并到单次 keySet 遍历
            if (shouldRenderPhone && typeIds.contains(BlackoutOverlayTypes.STREET_PHONE)) {
                renderCustomOverlay(renderContext, pos, PhoneOverlayRenderer.PHONE_OVERLAY_COLOR, PhoneOverlayRenderer.PHONE_OVERLAY_LINE_WIDTH);
                renderedCount++;
                continue;
            }

            // 检查任务方块
            if (!typeIds.contains(blockTypeId)) continue;

            Block cachedBlock = CustomTaskBlockCache.getBlockAt(pos);
            Block block = cachedBlock;
            if (block == null && level != null) {
                block = level.getBlockState(pos).getBlock();
            }

            if (block != null && block instanceof TaskInstinctShowableInterface)
                continue;

            if (isAddCoalTask) {
                if (hasCoal) {
                    if (block == Blocks.COAL_BLOCK) continue;
                } else {
                    if (block != Blocks.COAL_BLOCK) continue;
                }
            }

            if (isFurnaceExplosionTask) {
                if (hasTorch) {
                    if (block != Blocks.TNT) continue;
                } else {
                    if (block != Blocks.REDSTONE_TORCH) continue;
                }
            }

            renderCustomOverlay(renderContext, pos, taskColor, lineWidth);
            renderedCount++;
        }

        if (renderedCount > 0) {
            HabiTrainCore.LOGGER.debug("[HabiDebug] CustomTaskBlockRendererMixin: rendered {} blocks for task {}",
                    renderedCount, taskName != null ? taskName : "unknown");
        }
    }
}
