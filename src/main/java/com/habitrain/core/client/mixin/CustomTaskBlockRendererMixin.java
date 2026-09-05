package com.habitrain.core.client.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.client.render.BlockStageScanner;
import com.habitrain.core.client.render.GameRunningCache;
import com.habitrain.core.client.render.PhoneOverlayRenderer;
import com.habitrain.core.client.render.TaskOverlayDrawer;
import com.habitrain.core.client.render.ViewModeDispatcher;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.content.block.api.TaskInstinctShowableInterface;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.agmas.noellesroles.client.TaskBlockOverlayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;

/**
 * Injects custom DLC task block ESP into {@link TaskBlockOverlayRenderer#render}.
 *
 * <p>All drawing lives in {@link TaskOverlayDrawer} — mixin classes must not expose
 * non-private methods (Mixin InvalidMixinException previously killed this entire inject).
 *
 * <p>Color and outline width come from Mod Menu {@link TaskConfigEntry}, then task defaults.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = TaskBlockOverlayRenderer.class, remap = false)
public class CustomTaskBlockRendererMixin {

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private static void habitrain$renderCustomTaskBlocks(WorldRenderContext renderContext, CallbackInfo ci) {
        var instance = Minecraft.getInstance();
        if (instance == null || instance.player == null || instance.level == null) return;
        if (!GameRunningCache.isGameRunning()) return;

        if (SREClient.isPlayerSpectatingOrCreative()) {
            if (!CustomTaskBlockCache.isEmpty()) {
                ViewModeDispatcher.renderAll(renderContext);
            }
            return;
        }

        // Survival: ActiveTaskCache only (never client TaskManager singleton).
        String taskName = ActiveTaskCache.getActiveTaskFullId();
        String fakeName = ActiveTaskCache.getFakeTaskFullId();

        boolean hasEatOrDrink = isEatOrDrinkTask(taskName) || isEatOrDrinkTask(fakeName);
        if (CustomTaskBlockCache.isEmpty() && !hasEatOrDrink) {
            return;
        }

        if (taskName == null) {
            // Killer dual-task: fall back to fake task ESP when main is non-block / cleared.
            taskName = fakeName;
            if (taskName == null) {
                PhoneOverlayRenderer.render(renderContext);
                return;
            }
            renderTaskBlocks(renderContext, instance, taskName, true);
            PhoneOverlayRenderer.render(renderContext);
            return;
        }

        renderTaskBlocks(renderContext, instance, taskName, false);

        // Also outline fake task blocks when both are active and distinct.
        if (fakeName != null && !fakeName.equals(taskName)) {
            renderTaskBlocks(renderContext, instance, fakeName, true);
        }

        PhoneOverlayRenderer.render(renderContext);
    }

    private static boolean isEatOrDrinkTask(String taskName) {
        return HabiTrainCore.TASK_EAT.equals(taskName) || HabiTrainCore.TASK_DRINK.equals(taskName);
    }

    private static void renderTaskBlocks(
            WorldRenderContext renderContext,
            Minecraft instance,
            String taskName,
            boolean fake) {
        Color taskColor = resolveColor(taskName);
        float lineWidth = resolveOutlineWidth(taskName);
        int renderedCount = 0;

        // 桥接上游吃喝任务点：当玩家接到 Core 的 eat/drink 任务时，直接高亮 NoellesrolesClient.taskBlocks
        // (type 1: 食物, type 2: 饮品)
        if (isEatOrDrinkTask(taskName)) {
            int targetUpstreamType = HabiTrainCore.TASK_EAT.equals(taskName) ? 1 : 2;
            var upstreamBlocks = org.agmas.noellesroles.client.NoellesrolesClient.taskBlocks;
            if (upstreamBlocks != null && !upstreamBlocks.isEmpty()) {
                for (var entry : upstreamBlocks.entrySet()) {
                    if (entry.getValue() != null && entry.getValue() == targetUpstreamType) {
                        BlockPos pos = entry.getKey();
                        if (TaskOverlayDrawer.isInOverlayRange(renderContext, pos)) {
                            TaskOverlayDrawer.renderOverlay(renderContext, pos, taskColor, lineWidth);
                            renderedCount++;
                        }
                    }
                }
            }
        }

        int blockTypeId = resolveBlockTypeId(taskName);
        if (blockTypeId < BlackoutOverlayTypes.CUSTOM_OVERLAY_MIN_TYPE_ID) {
            if (renderedCount > 0) {
                HabiTrainCore.LOGGER.debug(
                        "[HabiDebug] CustomTaskBlockRendererMixin: rendered {} upstream-bridged blocks for {} task {}",
                        renderedCount, fake ? "fake" : "active", taskName);
            }
            return;
        }

        boolean isAddCoalTask = com.habitrain.core.HabiTrainCore.TASK_ADD_COAL.equals(taskName);
        boolean hasCoal = isAddCoalTask && BlockStageScanner.hasPlayerCoal(instance.player);

        boolean isFurnaceExplosionTask = com.habitrain.core.game.blackout.BlackoutExclusiveTasks.TASK_FURNACE_EXPLOSION.equals(taskName);
        boolean hasTorch = isFurnaceExplosionTask && BlockStageScanner.hasPlayerRedstoneTorch(instance.player);

        var level = renderContext.world();
        var upstreamBlocks = org.agmas.noellesroles.client.NoellesrolesClient.taskBlocks;
        for (BlockPos pos : CustomTaskBlockCache.positionsForType(blockTypeId)) {
            // 吃喝任务若已在上游 taskBlocks 中绘制过该位置，避免重复绘制
            if (isEatOrDrinkTask(taskName) && upstreamBlocks != null && upstreamBlocks.containsKey(pos)) {
                continue;
            }

            if (!TaskOverlayDrawer.isInOverlayRange(renderContext, pos)) continue;

            Block cachedBlock = CustomTaskBlockCache.getBlockAt(pos);
            Block block = cachedBlock;
            if (block == null && level != null) {
                block = level.getBlockState(pos).getBlock();
            }

            if (block != null && block instanceof TaskInstinctShowableInterface) {
                continue;
            }

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

            TaskOverlayDrawer.renderOverlay(renderContext, pos, taskColor, lineWidth);
            renderedCount++;
        }

        if (renderedCount > 0) {
            HabiTrainCore.LOGGER.debug(
                    "[HabiDebug] CustomTaskBlockRendererMixin: rendered {} blocks for {} task {}",
                    renderedCount, fake ? "fake" : "active", taskName);
        }
    }

    private static final Color FALLBACK_COLOR = new Color(200, 200, 200, 180);

    private static int resolveBlockTypeId(String taskFullId) {
        TaskDefinition def = TaskRegistry.get(taskFullId);
        return def != null ? def.getBlockTypeId() : -1;
    }

    private static String cachedColorTaskId;
    private static int cachedColorVersion = Integer.MIN_VALUE;
    private static Color cachedResolvedColor = FALLBACK_COLOR;

    private static Color resolveColor(String taskFullId) {
        int version = com.habitrain.core.client.InstinctColorHelper.getColorVersion();
        if (taskFullId != null && taskFullId.equals(cachedColorTaskId) && version == cachedColorVersion) {
            return cachedResolvedColor;
        }
        TaskDefinition def = TaskRegistry.get(taskFullId);
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(taskFullId);
        Color resolved = def != null
                ? new Color(com.habitrain.core.config.TaskInstinctColor.resolveArgb(cfg, def), true)
                : (cfg != null && cfg.hasInstinctColor
                ? new Color(cfg.getColor(), true)
                : FALLBACK_COLOR);
        cachedColorTaskId = taskFullId;
        cachedColorVersion = version;
        cachedResolvedColor = resolved;
        return resolved;
    }

    private static float resolveOutlineWidth(String taskFullId) {
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(taskFullId);
        if (cfg != null) {
            return cfg.outlineWidth;
        }
        return 4.0f;
    }
}
