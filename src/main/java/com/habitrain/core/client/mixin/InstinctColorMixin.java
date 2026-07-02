package com.habitrain.core.client.mixin;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.content.block.SecurityMonitorBlock;
import io.wifi.starrailexpress.content.block.api.TaskInstinctShowableInterface;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.agmas.noellesroles.client.NoellesrolesClient;
import org.agmas.noellesroles.client.TaskBlockOverlayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * 覆盖任务方块透视颜色。
 * 支持所有 blockTypeId（1-11 原版 + 12+ 自定义任务）
 */
@Environment(EnvType.CLIENT)
@Mixin(TaskBlockOverlayRenderer.class)
public class InstinctColorMixin {

    private static final Map<Integer, Color> overrideColors = new HashMap<>();

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private static void habitrain$buildOverrides(CallbackInfo ci) {
        overrideColors.clear();
        // 仅从 ModMenu 配置中读取颜色覆盖，不自动添加默认色
        for (TaskDefinition def : TaskRegistry.getAll()) {
            int bt = def.getBlockTypeId();
            if (bt < 1) continue;

            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
            if (cfg != null) {
                // 用户通过 ModMenu 显式设置了颜色
                overrideColors.put(bt, new Color(cfg.getColor(), true));
            }
            // 不再为 type >= 12 自动添加默认色——由 CustomTaskBlockRendererMixin 处理
        }
    }

    /**
     * 重定向 renderBlockOverlay，替换颜色，并在生存模式下抑制特定方块的透视
     *
     * ⚠️ 设计约束：
     * - 对于已实现 {@link TaskInstinctShowableInterface} 的方块（如 camera），
     *   它们有自己的 per-block 颜色逻辑（taskInstinctRenderColor()），
     *   所以不能应用 type-based 覆盖色，否则会导致这些方块的透视颜色被意外篡改。
     * - {@link SecurityMonitorBlock} 和售货机（type 11）在生存模式下不应透视，
     *   仅在旁观/创造模式下才显示。
     */
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/agmas/noellesroles/client/TaskBlockOverlayRenderer;renderBlockOverlay(Lnet/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext;Lnet/minecraft/core/BlockPos;Ljava/awt/Color;FZFLnet/minecraft/network/chat/Component;)V"
            ),
            remap = false,
            require = 0
    )
    private static void habitrain$redirectOverlay(
            WorldRenderContext ctx, BlockPos pos, Color color, float alpha,
            boolean colorize, float textScale, Component text) {
        Integer type = NoellesrolesClient.taskBlocks.get(pos);
        if (type != null) {
            var level = ctx.world();
            if (level != null) {
                var state = level.getBlockState(pos);
                var block = state.getBlock();

                // 生存模式下，抑制特定方块的透视
                if (!SREClient.isPlayerSpectatingOrCreative()) {
                    // SecurityMonitorBlock — shouldRenderTaskInstinct 无条件返回 true
                    if (block instanceof SecurityMonitorBlock) return;
                    // 售货机（type 11）— 原版硬编码 shouldDisplay[11] = true
                    if (type == 11) return;
                }

                // 已有独立颜色逻辑的方块 → 透传原始颜色
                if (block instanceof TaskInstinctShowableInterface) {
                    TaskBlockOverlayRenderer.renderBlockOverlay(
                            ctx, pos, color, alpha, colorize, textScale, text);
                    return;
                }
            }
            // 对于没有独立颜色逻辑的方块（如 vanilla 方块被 scanBlocks 扫描的），
            // 应用任务定义中的覆盖色
            Color override = overrideColors.get(type);
            if (override != null) {
                TaskBlockOverlayRenderer.renderBlockOverlay(
                        ctx, pos, override, alpha, colorize, textScale, text);
                return;
            }
        }
        // 无覆盖：透传原始颜色
        TaskBlockOverlayRenderer.renderBlockOverlay(
                ctx, pos, color, alpha, colorize, textScale, text);
    }
}
