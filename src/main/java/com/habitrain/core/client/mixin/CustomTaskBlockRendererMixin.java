package com.habitrain.core.client.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.taskapi.api.HabiTaskDefinition;
import com.habitrain.taskapi.api.HabiTaskRegistry;
import com.habitrain.taskapi.client.ActiveCustomTaskCache;
import com.habitrain.taskapi.impl.HabiTaskManager;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import com.habitrain.taskapi.impl.config.HabiTaskConfigEntry;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.client.NoellesrolesClient;
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

/**
 * Mixin - 注入 {@link TaskBlockOverlayRenderer#render(WorldRenderContext)} 方法
 *
 * 在渲染循环的末尾添加自定义DLC任务方块的高亮边框。
 *
 * 设计要点：
 * - 旁观/创造模式：渲染所有自定义任务方块（类型 ≥12），无需活跃任务
 *   → 与原始 render() 中 shouldDisplay[1..11] = true 的行为一致
 * - 生存模式：仅渲染玩家当前活跃的自定义任务匹配的方块
 * - 颜色从 {@link HabiTaskDefinition#getInstinctColor()} 获取，
 *   如有 ModMenu 配置则使用配置颜色（与 {@link InstinctColorMixin} 逻辑一致）
 * - 透明度使用 Color 自身的 alpha 通道（不再硬编码 0.2f）
 * - 描边粗细可从配置读取（SRE 默认 4.0）
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
     * 使用可配置粗细和 Color 自身 alpha 的自定义渲染
     *
     * 与 {@link TaskBlockOverlayRenderer#renderBlockOverlay} 逻辑相同，
     * 但使用可配置线宽的 RenderType 取代硬编码的 4.0，
     * 且 alpha 取自 Color 的 alpha 通道（而非硬编码参数）。
     */
    private static void renderCustomOverlay(
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

    // ====== 颜色映射 ======

    /**
     * 构建 blockTypeId → Color 的映射
     * 优先使用 ModMenu 配置颜色，其次使用任务定义默认颜色
     */
    private static Map<Integer, Color> buildTypeColorMap() {
        Map<Integer, Color> map = new HashMap<>();
        for (HabiTaskDefinition def : HabiTaskRegistry.getAll()) {
            int bt = def.getBlockTypeId();
            if (bt < 12) continue;

            HabiTaskConfigEntry cfg = HabiConfigManager.getInstance().getTaskConfig(def.getFullId());
            if (cfg != null) {
                map.put(bt, cfg.getColor());
            } else if (def.getInstinctColor() != null) {
                map.put(bt, def.getInstinctColor());
            } else {
                map.put(bt, new Color(200, 200, 200, 180));
            }
        }
        return map;
    }

    // ====== @Inject(TAIL) - 主渲染入口 ======

    /**
     * 在原始渲染末尾加入自定义任务的方块高亮
     */
    @Inject(
            method = "render",
            at = @At("TAIL"),
            remap = false
    )
    private static void habitrain$renderCustomTaskBlocks(WorldRenderContext renderContext, CallbackInfo ci) {
        var instance = Minecraft.getInstance();
        if (instance == null || instance.player == null || instance.level == null) return;

        if (NoellesrolesClient.taskBlocks.isEmpty()) return;

        // ===== 旁观/创造模式 =====
        if (SREClient.isPlayerSpectatingOrCreative()) {
            renderAllCustomTaskBlocks(renderContext);
            return;
        }

        // ===== 生存模式：需要活跃的自定义任务 =====
        // ★ 优先从 HabiTaskManager 读取（单机模式，共享 JVM）
        // ★ 回退到 ActiveCustomTaskCache（多人模式，从服务端同步）
        HabiTaskManager mgr = HabiTaskManager.getInstance();
        var customTask = mgr.getActiveCustomTask(instance.player.getUUID());

        int blockTypeId;
        Color taskColor;
        float lineWidth = 4.0f; // SRE 默认线宽

        if (customTask != null) {
            // 单机模式：直接从 HabiTaskManager 获取
            blockTypeId = customTask.getDefinition().getBlockTypeId();
            if (blockTypeId < 12) return;

            HabiTaskConfigEntry cfg = HabiConfigManager.getInstance().getTaskConfig(customTask.getFullId());
            if (cfg != null) {
                taskColor = cfg.getColor();
                lineWidth = cfg.outlineWidth;
            } else {
                taskColor = customTask.getDefinition().getInstinctColor();
                if (taskColor == null) taskColor = new Color(200, 200, 200, 180);
            }
        } else {
            // ★ 多人模式：从服务端同步的缓存获取
            String activeTaskId = ActiveCustomTaskCache.getActiveTaskFullId();
            if (activeTaskId == null) return; // 没有活跃的任务

            blockTypeId = ActiveCustomTaskCache.getBlockTypeId();
            if (blockTypeId < 12) return;

            taskColor = ActiveCustomTaskCache.getColor();
            lineWidth = ActiveCustomTaskCache.getOutlineWidth();
        }

        // 记录任务名称（用于日志）
        String taskName = customTask != null ? customTask.getFullId() : ActiveCustomTaskCache.getActiveTaskFullId();

        int renderedCount = 0;
        var level = renderContext.world();
        for (Map.Entry<BlockPos, Integer> entry : NoellesrolesClient.taskBlocks.entrySet()) {
            if (entry.getValue() == blockTypeId) {
                // 跳过已有独立颜色逻辑的方块
                if (level != null && level.getBlockState(entry.getKey()).getBlock() instanceof TaskInstinctShowableInterface)
                    continue;

                renderCustomOverlay(renderContext, entry.getKey(), taskColor, lineWidth);
                renderedCount++;
            }
        }

        if (renderedCount > 0) {
            HabiTrainCore.LOGGER.info("[HabiDebug] CustomTaskBlockRendererMixin: rendered {} blocks for task {}",
                    renderedCount, taskName != null ? taskName : "unknown");
        }
    }

    // ====== 旁观/创造模式渲染 ======

    /**
     * 旁观/创造模式：渲染所有已注册的自定义任务方块（类型 ≥12）
     * 对应原始 render() 中旁观/创造模式下 shouldDisplay[1..11] = true 的行为
     *
     * 注意：旁观模式没有"活跃任务"，因此描边粗细使用 SRE 默认值 4.0。
     */
    private static void renderAllCustomTaskBlocks(WorldRenderContext renderContext) {
        Map<Integer, Color> typeColors = buildTypeColorMap();
        if (typeColors.isEmpty()) return;

        int renderedCount = 0;
        var level = renderContext.world();
        for (Map.Entry<BlockPos, Integer> entry : NoellesrolesClient.taskBlocks.entrySet()) {
            int type = entry.getValue();
            if (type >= 12) {
                // 跳过已有独立颜色逻辑的方块
                if (level != null && level.getBlockState(entry.getKey()).getBlock() instanceof TaskInstinctShowableInterface)
                    continue;

                Color color = typeColors.get(type);
                if (color != null) {
                    renderCustomOverlay(renderContext, entry.getKey(), color, 4.0f);
                    renderedCount++;
                }
            }
        }

        if (renderedCount > 0) {
            HabiTrainCore.LOGGER.info("[HabiDebug] CustomTaskBlockRendererMixin: rendered {} custom task blocks (spectating/creative)", renderedCount);
        }
    }
}
