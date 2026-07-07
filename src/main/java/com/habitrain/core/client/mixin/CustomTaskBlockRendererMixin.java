package com.habitrain.core.client.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.client.InstinctColorHelper;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import com.habitrain.core.task.TaskManager;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.content.block.api.TaskInstinctShowableInterface;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 *   如有 ModMenu 配置则使用配置颜色（与 {@link InstinctColorMixin} 逻辑一致）
 * - 透明度使用 Color 自身的 alpha 通道（不再硬编码 0.2f）
 * - 描边粗细可从配置读取（SRE 默认 4.0）
 */
    @Environment(EnvType.CLIENT)
    @Mixin(TaskBlockOverlayRenderer.class)
    public class CustomTaskBlockRendererMixin {

    // 性能优化：渲染节流计数器（每 2 帧渲染一次）
    private static int renderSkipCounter = 0;

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

    // ====== 游戏状态检测（带缓存）======

    /**
     * 检测 SRE 游戏是否正在运行
     * 大厅阶段返回 false，游戏进行中返回 true
     *
     * 性能优化：缓存结果 10 tick（0.5 秒），避免每帧查询 Cardinal 组件。
     * Cardinal 组件查询每次都会做 LevelAttachedData 查找，每帧调用造成无谓开销。
     */
    private static volatile long gameRunningCacheExpireMs = 0;
    private static volatile boolean gameRunningCachedValue = false;

    private static boolean isGameRunning() {
        long now = System.currentTimeMillis();
        if (now < gameRunningCacheExpireMs) {
            return gameRunningCachedValue;
        }
        var instance = Minecraft.getInstance();
        if (instance == null || instance.level == null) {
            gameRunningCachedValue = false;
        } else {
            try {
                var gameWorld = SREGameWorldComponent.KEY.get(instance.level);
                gameRunningCachedValue = gameWorld != null && gameWorld.isRunning();
            } catch (Exception e) {
                gameRunningCachedValue = false;
            }
        }
        // 缓存 500ms（10 tick）
        gameRunningCacheExpireMs = now + 500L;
        return gameRunningCachedValue;
    }

    /** 强制刷新缓存（在 SRE 游戏开始/结束事件时调用） */
    private static void invalidateGameRunningCache() {
        gameRunningCacheExpireMs = 0;
    }

    // ====== 颜色映射 ======

    /**
     * 构建 blockTypeId → Color 的映射
     * 优先使用 ModMenu 配置颜色，其次使用任务定义默认颜色
     */
    private static Map<Integer, Color> cachedTypeColorMap = null;
    private static int cachedColorVersion = -1;

    private static Map<Integer, Color> buildTypeColorMap() {
        // 缓存：仅在配置颜色变更（InstinctColorHelper.colorVersion 改变）或首次调用时重建，
        // 避免每帧分配 HashMap + 全表扫描 TaskRegistry.getAll()。
        if (cachedTypeColorMap != null && cachedColorVersion == InstinctColorHelper.getColorVersion()) {
            return cachedTypeColorMap;
        }
        Map<Integer, Color> map = new HashMap<>();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            int bt = def.getBlockTypeId();
            if (bt < 12) continue;

            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
            if (cfg != null) {
                map.put(bt, new Color(cfg.getColor(), true));
            } else {
                map.put(bt, new Color(def.getInstinctColorRGB(), true));
            }
        }
        cachedTypeColorMap = map;
        cachedColorVersion = InstinctColorHelper.getColorVersion();
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
        // 性能优化：节流渲染，每 2 帧渲染一次。方块描边是静态世界位置，
        // 每帧重渲染意义不大但开销大（每位置 1 次 getBlockState + LevelRenderer.renderLineBox）。
        renderSkipCounter = (renderSkipCounter + 1) & 0x1;  // 0 → 1 → 0 → 1 ...
        if (renderSkipCounter != 0) return;

        var instance = Minecraft.getInstance();
        if (instance == null || instance.player == null || instance.level == null) return;

        if (CustomTaskBlockCache.isEmpty()) return;

        // ===== 大厅阶段（无活跃游戏）→ 不渲染任何自定义任务方块 =====
        if (!isGameRunning()) return;

        // ===== 旁观/创造模式 =====
        if (SREClient.isPlayerSpectatingOrCreative()) {
            renderAllCustomTaskBlocks(renderContext);
            return;
        }

        // ===== 生存模式：需要活跃的自定义任务 =====
        // ★ 优先从 TaskManager 读取（单机模式，共享 JVM）
        // ★ 回退到 ActiveTaskCache（多人模式，从服务端同步）
        TaskManager mgr = TaskManager.getInstance();
        var customTask = mgr.getActiveTask(instance.player.getUUID());

        int blockTypeId;
        Color taskColor;
        float lineWidth = 4.0f; // SRE 默认线宽

        if (customTask != null) {
            // 单机模式：直接从 TaskManager 获取
            blockTypeId = customTask.getDefinition().getBlockTypeId();
            if (blockTypeId < 12) return;
            if (blockTypeId == 12) return;

            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(customTask.getFullId());
            if (cfg != null) {
                taskColor = new Color(cfg.getColor(), true);
                lineWidth = cfg.outlineWidth;
            } else {
                taskColor = new Color(customTask.getDefinition().getInstinctColorRGB(), true);
            }
        } else {
            // ★ 多人模式：从服务端同步的缓存获取
            String activeTaskId = ActiveTaskCache.getActiveTaskFullId();
            if (activeTaskId == null) return; // 没有活跃的任务

            blockTypeId = ActiveTaskCache.getBlockTypeId();
            if (blockTypeId < 12) return;
            if (blockTypeId == 12) return;

            taskColor = ActiveTaskCache.getColor();
            lineWidth = ActiveTaskCache.getOutlineWidth();
        }

        // 记录任务名称（用于日志）
        String taskName = customTask != null ? customTask.getFullId() : ActiveTaskCache.getActiveTaskFullId();

        // 添煤任务按阶段切换渲染目标：
        // 阶段0（无煤炭）→ 只渲染 COAL_BLOCK 位置；阶段1（有煤炭）→ 只渲染 generator 位置
        boolean isAddCoalTask = "habitrain_core:add_coal".equals(taskName);
        boolean hasCoal = isAddCoalTask && hasPlayerCoal(instance.player);

        // 炸毁熔炉任务按阶段切换渲染目标：
        // 阶段0（无红石火把）→ 只渲染 REDSTONE_TORCH 位置；阶段1（有红石火把）→ 只渲染 TNT 位置
        boolean isFurnaceExplosionTask = "habitrain_core:furnace_explosion".equals(taskName);
        boolean hasTorch = isFurnaceExplosionTask && hasPlayerRedstoneTorch(instance.player);

        int renderedCount = 0;
        var level = renderContext.world();
        for (BlockPos pos : CustomTaskBlockCache.keySet()) {
            Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
            if (typeIds == null || !typeIds.contains(blockTypeId)) continue;

            // 性能优化：优先读缓存的 Block，避免每位置每帧 level.getBlockState(pos) 查询
            Block cachedBlock = CustomTaskBlockCache.getBlockAt(pos);
            Block block = cachedBlock;
            if (block == null && level != null) {
                // 缓存未命中（旧数据/网络同步过来无 Block 信息），回退到 getBlockState
                block = level.getBlockState(pos).getBlock();
            }

            if (block != null && block instanceof TaskInstinctShowableInterface)
                continue;

            // 添煤阶段过滤
            if (isAddCoalTask) {
                if (hasCoal) {
                    if (block == Blocks.COAL_BLOCK) continue;
                } else {
                    if (block != Blocks.COAL_BLOCK) continue;
                }
            }

            // 炸毁熔炉阶段过滤
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

    // ====== 添煤任务阶段检测（缓存版） ======

    // 背包缓存：避免每帧扫描背包（40+ 格 for 循环）导致微卡顿。
    // 缓存有效期 40 tick（≈2 秒），之后才重新扫描。
    private static final long INVENTORY_CACHE_TTL_MS = 2000L;
    private static long lastCoalCheckTime = 0;
    private static boolean cachedHasCoal = false;
    private static long lastTorchCheckTime = 0;
    private static boolean cachedHasTorch = false;

    /**
     * 缓存版 hasPlayerCoal，最多每 2 秒扫描一次背包
     */
    private static boolean hasPlayerCoal(net.minecraft.world.entity.player.Player player) {
        if (player == null) return false;
        long now = System.currentTimeMillis();
        if (now - lastCoalCheckTime < INVENTORY_CACHE_TTL_MS) {
            return cachedHasCoal;
        }
        lastCoalCheckTime = now;
        cachedHasCoal = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.COAL)) {
                cachedHasCoal = true;
                break;
            }
        }
        return cachedHasCoal;
    }

    /**
     * 缓存版 hasPlayerRedstoneTorch，最多每 2 秒扫描一次背包
     */
    private static boolean hasPlayerRedstoneTorch(net.minecraft.world.entity.player.Player player) {
        if (player == null) return false;
        long now = System.currentTimeMillis();
        if (now - lastTorchCheckTime < INVENTORY_CACHE_TTL_MS) {
            return cachedHasTorch;
        }
        lastTorchCheckTime = now;
        cachedHasTorch = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.REDSTONE_TORCH)) {
                cachedHasTorch = true;
                break;
            }
        }
        return cachedHasTorch;
    }

    // ====== 旁观/创造模式渲染 ======

    /**
     * 旁观/创造模式：渲染所有已注册的自定义任务方块（类型 ≥12）
     * 对应原始 render() 中旁观/创造模式下 shouldDisplay[1..11] = true 的行为
     *
     * 注意：旁观模式没有"活跃任务"，因此描边粗细使用 SRE 默认值 4.0。
     */
    private static void renderAllCustomTaskBlocks(WorldRenderContext renderContext) {
        // ★ 大厅阶段（无活跃游戏）→ 不渲染 DLC 自定义方块（type ≥ 12）
        //    只让 SRE 原版渲染器处理原版方块（type 1-11）
        if (!isGameRunning()) {
            return;
        }

        Map<Integer, Color> typeColors = buildTypeColorMap();
        if (typeColors.isEmpty()) return;

        int renderedCount = 0;
        var level = renderContext.world();
        for (BlockPos pos : CustomTaskBlockCache.keySet()) {
            Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
            if (typeIds == null) continue;

            // 性能优化：优先读缓存的 Block，避免每位置每帧 level.getBlockState(pos)
            Block cachedBlock = CustomTaskBlockCache.getBlockAt(pos);
            Block block = cachedBlock;
            if (block == null && level != null) {
                block = level.getBlockState(pos).getBlock();
            }
            if (block != null && block instanceof TaskInstinctShowableInterface)
                continue;

            for (int type : typeIds) {
                if (type == 12) continue;
                Color color = typeColors.get(type);
                if (color != null) {
                    renderCustomOverlay(renderContext, pos, color, 4.0f);
                    renderedCount++;
                    break;
                }
            }
        }

        if (renderedCount > 0) {
            HabiTrainCore.LOGGER.debug("[HabiDebug] CustomTaskBlockRendererMixin: rendered {} custom task blocks (spectating/creative)", renderedCount);
        }
    }
}
