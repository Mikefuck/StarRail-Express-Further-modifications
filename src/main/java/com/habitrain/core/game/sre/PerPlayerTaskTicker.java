package com.habitrain.core.game.sre;

import com.habitrain.core.api.ItemReclaimHelper;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.TaskManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PerPlayerTaskTicker {
    private static final Logger LOGGER = LoggerFactory.getLogger("PerPlayerTaskTicker");

    private PerPlayerTaskTicker() {
    }

    public static void tick(Player player) {
        TaskManager mgr = TaskManager.getInstance();
        TaskInstance customTask = mgr.getActiveTask(player.getUUID());
        TaskInstance fakeTask = mgr.getFakeTask(player.getUUID());

        if (customTask != null) {
            customTask.tick(player);
            if (customTask.isFulfilled()) {
                handleMainTaskDone(mgr, customTask, player);
            }
        }

        if (fakeTask != null) {
            fakeTask.tick(player);
            if (fakeTask.isFulfilled()) {
                handleFakeTaskDone(mgr, fakeTask, player);
            }
        }
    }

    private static void handleMainTaskDone(TaskManager mgr, TaskInstance customTask, Player player) {
        if (customTask.isFailed()) {
            LOGGER.debug("[HabiDebug] Custom task {} failed, removing tracking without completion reward",
                    customTask.getFullId());
            try {
                customTask.getDefinition().onRemove(player, customTask);
            } catch (Throwable t) {
                LOGGER.error("onRemove callback failed: {}", customTask.getFullId(), t);
            }
            ItemReclaimHelper.reclaimForTask(player, customTask);
            mgr.removeActiveTask(player.getUUID());
            if (player instanceof ServerPlayer sp) {
                ActiveTaskPayload.clearForPlayer(sp);
            }
        } else {
            LOGGER.debug("[HabiDebug] Custom task {} fulfilled, removing tracking", customTask.getFullId());
            if (player instanceof ServerPlayer sp) {
                mgr.handleTaskCompletion(sp, customTask);
                ActiveTaskPayload.clearForPlayer(sp);
            }
        }
    }

    private static void handleFakeTaskDone(TaskManager mgr, TaskInstance fakeTask, Player player) {
        if (fakeTask.isFailed()) {
            LOGGER.info("[KillerDualTask] fake task {} failed for {}",
                    fakeTask.getFullId(), player.getName().getString());
            mgr.removeFakeTask(player.getUUID());
            if (player instanceof ServerPlayer sp) {
                ActiveTaskPayload.clearForPlayer(sp, true);
            }
        } else {
            LOGGER.info("[KillerDualTask] fake task {} fulfilled for {}, granting rewards",
                    fakeTask.getFullId(), player.getName().getString());
            if (player instanceof ServerPlayer sp) {
                mgr.handleTaskCompletion(sp, fakeTask);
                ActiveTaskPayload.clearForPlayer(sp, true);
            }
            mgr.removeFakeTask(player.getUUID());
        }
    }

    public static boolean mgrHasActiveDlcTask(Player player) {
        return TaskManager.getInstance().getActiveTask(player.getUUID()) != null;
    }
}
