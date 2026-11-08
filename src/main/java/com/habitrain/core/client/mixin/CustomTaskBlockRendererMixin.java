package com.habitrain.core.client.mixin;


import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.client.render.GameRunningCache;
import com.habitrain.core.client.render.TaskOverlayDrawer;
import com.habitrain.core.client.render.ViewModeDispatcher;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import com.habitrain.core.client.cache.ClientTaskBlockCache;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.content.block.api.TaskInstinctShowableInterface;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
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
            if (!ClientTaskBlockCache.isEmpty()) {
                ViewModeDispatcher.renderAll(renderContext);
            }
            return;
        }

        // Survival: ActiveTaskCache only (never client TaskManager singleton).
        String taskName = ActiveTaskCache.getActiveTaskFullId();
        if (taskName == null || ClientTaskBlockCache.isEmpty()) {
            return;
        }

        renderTaskBlocks(renderContext, taskName);
    }

    private static void renderTaskBlocks(WorldRenderContext renderContext, String taskName) {
        Color taskColor = resolveColor(taskName);
        float lineWidth = resolveOutlineWidth(taskName);
        int renderedCount = 0;

        int blockTypeId = resolveBlockTypeId(taskName);
        if (blockTypeId < com.habitrain.core.game.sre.task.CustomTaskBlockIndexLimits.CUSTOM_OVERLAY_MIN_TYPE_ID) {
            return;
        }

        var level = renderContext.world();
        for (BlockPos pos : ClientTaskBlockCache.positionsForType(blockTypeId)) {
            if (!TaskOverlayDrawer.isInOverlayRange(renderContext, pos)) continue;

            Block cachedBlock = ClientTaskBlockCache.getBlockAt(pos);
            Block block = cachedBlock;
            if (block == null && level != null) {
                block = level.getBlockState(pos).getBlock();
            }

            if (block != null && block instanceof TaskInstinctShowableInterface) {
                continue;
            }

            TaskOverlayDrawer.renderOverlay(renderContext, pos, taskColor, lineWidth);
            renderedCount++;
        }

        if (renderedCount > 0) {
            HabiTrainCore.LOGGER.debug(
                    "[HabiDebug] CustomTaskBlockRendererMixin: rendered {} blocks for task {}",
                    renderedCount, taskName);
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
        com.habitrain.core.config.MinigameConfigEntry mgCfg = resolveMinigameConfig(taskFullId);
        Color resolved;
        if (mgCfg != null && mgCfg.hasInstinctColor) {
            resolved = new Color(mgCfg.getColor(), true);
        } else if (def != null) {
            resolved = new Color(com.habitrain.core.config.TaskInstinctColor.resolveArgb(cfg, def), true);
        } else if (cfg != null && cfg.hasInstinctColor) {
            resolved = new Color(cfg.getColor(), true);
        } else {
            resolved = FALLBACK_COLOR;
        }
        cachedColorTaskId = taskFullId;
        cachedColorVersion = version;
        cachedResolvedColor = resolved;
        return resolved;
    }

    private static float resolveOutlineWidth(String taskFullId) {
        com.habitrain.core.config.MinigameConfigEntry mgCfg = resolveMinigameConfig(taskFullId);
        if (mgCfg != null && mgCfg.getOutlineWidth() > 0.0f) {
            return mgCfg.getOutlineWidth();
        }
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(taskFullId);
        if (cfg != null && cfg.outlineWidth > 0.0f) {
            return cfg.outlineWidth;
        }
        return 4.0f;
    }

    private static com.habitrain.core.config.MinigameConfigEntry resolveMinigameConfig(String taskFullId) {
        if (taskFullId == null || taskFullId.isEmpty()) {
            return null;
        }
        var allMinigames = ConfigManager.getInstance().getAllMinigameConfigs();
        var direct = allMinigames.get(taskFullId);
        if (direct != null) {
            return direct;
        }
        int colon = taskFullId.indexOf(':');
        if (colon >= 0 && colon + 1 < taskFullId.length()) {
            return allMinigames.get(taskFullId.substring(colon + 1));
        }
        return null;
    }
}
