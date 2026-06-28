package com.habitrain.taskapi.impl.mixin;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import com.habitrain.taskapi.api.HabiTaskInstance;
import com.habitrain.taskapi.impl.HabiTaskManager;
import com.habitrain.taskapi.impl.network.ActiveCustomTaskPayload;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin - 注入 {@link SREPlayerTaskComponent} 的生命周期方法
 * 负责 tick API 独立跟踪的自定义任务以及在游戏重置时清理跟踪状态
 */
@Mixin(SREPlayerTaskComponent.class)
public class ServerTickMixin {

    @Shadow(remap = false)
    private Player player;

    /**
     * 注入 init() 方法 - 游戏重置/新回合开始时的清理
     * 修复：当SRE清空任务时，同时清理API的独立跟踪系统并通知客户端
     */
    @Inject(
            method = "init",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onInit(CallbackInfo ci) {
        if (player != null) {
            HabiTaskManager mgr = HabiTaskManager.getInstance();
            HabiTaskInstance oldTask = mgr.getActiveCustomTask(player.getUUID());
            if (oldTask != null) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug] init() called - clearing activeCustomTask {} for player {}",
                        oldTask.getFullId(), player.getName().getString());
                mgr.removeActiveCustomTask(player.getUUID());

                // 同步清空活跃任务到客户端
                if (player instanceof ServerPlayer sp) {
                    ActiveCustomTaskPayload.clearForPlayer(sp);
                }
            }
        }
    }

    /**
     * 注入 clear() 方法 - 清理时的额外清理
     */
    @Inject(
            method = "clear",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onClear(CallbackInfo ci) {
        if (player != null) {
            HabiTaskManager mgr = HabiTaskManager.getInstance();
            mgr.removeActiveCustomTask(player.getUUID());
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] clear() called - removed activeCustomTask for player {}",
                    player.getName().getString());

            // 同步清空活跃任务到客户端
            if (player instanceof ServerPlayer sp) {
                ActiveCustomTaskPayload.clearForPlayer(sp);
            }
        }
    }

    /**
     * 在 serverTick 中处理 API 自定义任务的 tick 和完成清理
     */
    @Inject(
            method = "serverTick",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onServerTick(CallbackInfo ci) {
        if (player == null) return;

        HabiTaskManager mgr = HabiTaskManager.getInstance();
        HabiTaskInstance customTask = mgr.getActiveCustomTask(player.getUUID());

        if (customTask == null) return;

        // tick 自定义任务 (触发 onTick 和完成检测)
        customTask.tick(player);

        // 如果任务已完成，从 API 跟踪系统中移除
        if (customTask.isFulfilled()) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] Custom task {} fulfilled, removing tracking", customTask.getFullId());
            mgr.removeActiveCustomTask(player.getUUID());

            // ★ 同步清空活跃任务到客户端（多人模式下清除透视）
            if (player instanceof ServerPlayer sp) {
                ActiveCustomTaskPayload.clearForPlayer(sp);
            }
        }
    }
}
