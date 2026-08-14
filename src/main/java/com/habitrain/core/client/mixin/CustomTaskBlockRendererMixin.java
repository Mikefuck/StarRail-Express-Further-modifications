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
import java.util.Set;

/**
 * Injects custom DLC task block ESP into {@link TaskBlockOverlayRenderer#render}.
 *
 * <p>All drawing lives in {@link TaskOverlayDrawer} — mixin classes must not expose
 * non-private methods (Mixin InvalidMixinException previously killed this entire inject).
 */
@Environment(EnvType.CLIENT)
@Mixin(TaskBlockOverlayRenderer.class)
public class CustomTaskBlockRendererMixin {

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private static void habitrain$renderCustomTaskBlocks(WorldRenderContext renderContext, CallbackInfo ci) {
        var instance = Minecraft.getInstance();
        if (instance == null || instance.player == null || instance.level == null) return;
        if (CustomTaskBlockCache.isEmpty()) return;
        if (!GameRunningCache.isGameRunning()) return;

        if (SREClient.isPlayerSpectatingOrCreative()) {
            ViewModeDispatcher.renderAll(renderContext);
            PhoneOverlayRenderer.render(renderContext);
            return;
        }

        // Survival: ActiveTaskCache only (never client TaskManager singleton).
        String taskName = ActiveTaskCache.getActiveTaskFullId();
        if (taskName == null) {
            // Killer dual-task: fall back to fake task ESP when main is non-block / cleared.
            taskName = ActiveTaskCache.getFakeTaskFullId();
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
        String fakeName = ActiveTaskCache.getFakeTaskFullId();
        if (fakeName != null && !fakeName.equals(taskName)) {
            renderTaskBlocks(renderContext, instance, fakeName, true);
        }

        PhoneOverlayRenderer.render(renderContext);
    }

    private static void renderTaskBlocks(
            WorldRenderContext renderContext,
            Minecraft instance,
            String taskName,
            boolean fake) {
        int blockTypeId = resolveBlockTypeId(taskName);
        if (blockTypeId <= 12) return;

        Color taskColor = resolveColor(taskName);
        float lineWidth = resolveOutlineWidth(taskName);

        boolean isAddCoalTask = com.habitrain.core.HabiTrainCore.TASK_ADD_COAL.equals(taskName);
        boolean hasCoal = isAddCoalTask && BlockStageScanner.hasPlayerCoal(instance.player);

        boolean isFurnaceExplosionTask = com.habitrain.core.game.blackout.BlackoutExclusiveTasks.TASK_FURNACE_EXPLOSION.equals(taskName);
        boolean hasTorch = isFurnaceExplosionTask && BlockStageScanner.hasPlayerRedstoneTorch(instance.player);

        boolean shouldRenderPhone = com.habitrain.core.client.gui.ClientBlackoutState.isBlackoutModeActive();

        int renderedCount = 0;
        var level = renderContext.world();
        for (BlockPos pos : CustomTaskBlockCache.keySet()) {
            Set<Integer> typeIds = CustomTaskBlockCache.get(pos);
            if (typeIds == null) continue;

            // Phone constant overlay is handled by PhoneOverlayRenderer; skip here to avoid double-draw.
            if (shouldRenderPhone && typeIds.contains(BlackoutOverlayTypes.STREET_PHONE)
                    && !typeIds.contains(blockTypeId)) {
                continue;
            }

            if (!typeIds.contains(blockTypeId)) continue;

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

    private static Color resolveColor(String taskFullId) {
        Color resolved;
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(taskFullId);
        if (cfg != null) {
            resolved = new Color(cfg.getColor(), true);
        } else {
            TaskDefinition def = TaskRegistry.get(taskFullId);
            if (def != null) {
                resolved = new Color(def.getInstinctColorRGB(), true);
            } else {
                return FALLBACK_COLOR;
            }
        }
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
