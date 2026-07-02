package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.network.ActiveTaskPayload;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SREPlayerTaskComponent.class)
public class ServerTickMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("ServerTickMixin");

    @Shadow(remap = false)
    private Player player;

    @Inject(
            method = "init",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onInit(CallbackInfo ci) {
        if (player != null) {
            TaskManager mgr = TaskManager.getInstance();
            TaskInstance oldTask = mgr.getActiveTask(player.getUUID());
            if (oldTask != null) {
                LOGGER.info("[HabiDebug] init() called - clearing activeCustomTask {} for player {}",
                        oldTask.getFullId(), player.getName().getString());
                mgr.removeActiveTask(player.getUUID());

                if (player instanceof ServerPlayer sp) {
                    ActiveTaskPayload.clearForPlayer(sp);
                }
            }
        }
    }

    @Inject(
            method = "clear",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onClear(CallbackInfo ci) {
        if (player != null) {
            TaskManager mgr = TaskManager.getInstance();
            mgr.removeActiveTask(player.getUUID());
            LOGGER.info("[HabiDebug] clear() called - removed activeCustomTask for player {}",
                    player.getName().getString());

            if (player instanceof ServerPlayer sp) {
                ActiveTaskPayload.clearForPlayer(sp);
            }
        }
    }

    @Inject(
            method = "serverTick",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onServerTick(CallbackInfo ci) {
        if (player == null) return;

        TaskManager mgr = TaskManager.getInstance();
        TaskInstance customTask = mgr.getActiveTask(player.getUUID());

        if (customTask == null) return;

        customTask.tick(player);

        if (customTask.isFulfilled()) {
            LOGGER.info("[HabiDebug] Custom task {} fulfilled, removing tracking", customTask.getFullId());
            if (player instanceof ServerPlayer sp) {
                mgr.handleTaskCompletion(sp, customTask);
                ActiveTaskPayload.clearForPlayer(sp);
            }
        }
    }
}
