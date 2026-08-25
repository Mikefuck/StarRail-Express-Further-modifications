package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.blackout.BlackoutExclusiveTasks;
import com.habitrain.core.game.blackout.ExclusiveTaskHudSync;
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
        // 维修人员不参与对局：不 tick/不派发任何 api 任务（双保险，SRE 本就不给其开局名单）
        if (RepairModeManager.isRepairer(player)) {
            return;
        }
        TaskManager mgr = TaskManager.getInstance();
        TaskInstance customTask = mgr.getActiveTask(player.getUUID());
        TaskInstance fakeTask = mgr.getFakeTask(player.getUUID());
        if (customTask == null && fakeTask == null) {
            return;
        }
        if (!canTickCustomTasks(player)) {
            // 非 ACTIVE / 死亡 / 休息区 / 旁观 / 创造：回收并卸任务，避免 STOPPING 改胜负。
            mgr.cancelAllTrackedTasks(player);
            return;
        }

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

    /**
     * 仅 SRE ACTIVE 且存活、非旁观/创造、非休息区才推进自定义任务。
     * STOPPING / STARTING / INACTIVE 都不 tick，避免结算淡出改胜负或休息区完成。
     */
    static boolean canTickCustomTasks(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return false;
        }
        if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
            return false;
        }
        if (EliminatedRestAreaService.isResting(sp)) {
            return false;
        }
        try {
            if (player.level() == null) {
                return false;
            }
            var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(player.level());
            return gw != null
                    && gw.getGameStatus() == io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.ACTIVE;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void handleMainTaskDone(TaskManager mgr, TaskInstance customTask, Player player) {
        boolean exclusive = BlackoutExclusiveTasks.isExclusive(customTask.getFullId());
        if (customTask.isFailed()) {
            LOGGER.debug("[HabiDebug] Custom task {} failed, removing tracking without completion reward",
                    customTask.getFullId());
            mgr.cancelTrackedTask(player, customTask, false);
            if (exclusive && player instanceof ServerPlayer sp) {
                // 立刻恢复原版派发，避免左上角空白后再闪
                ExclusiveTaskHudSync.resumeVanillaDispatch(sp);
            }
        } else {
            LOGGER.debug("[HabiDebug] Custom task {} fulfilled, removing tracking", customTask.getFullId());
            if (player instanceof ServerPlayer sp) {
                // 先摘 SRE wrapper，避免本方法返回后上游 serverTick 再 callOnFinishQuest。
                DlcTaskTracker.stripSreWrapper(sp, customTask);
                mgr.handleTaskCompletion(sp, customTask);
                ActiveTaskPayload.clearForPlayer(sp);
                if (exclusive) {
                    ExclusiveTaskHudSync.resumeVanillaDispatch(sp);
                } else {
                    ExclusiveTaskHudSync.clear(sp);
                }
            }
        }
    }

    private static void handleFakeTaskDone(TaskManager mgr, TaskInstance fakeTask, Player player) {
        if (fakeTask.isFailed()) {
            LOGGER.info("[KillerDualTask] fake task {} failed for {}",
                    fakeTask.getFullId(), player.getName().getString());
            mgr.cancelTrackedTask(player, fakeTask, true);
        } else {
            LOGGER.info("[KillerDualTask] fake task {} fulfilled for {}, clearing slot without true completion",
                    fakeTask.getFullId(), player.getName().getString());
            if (player instanceof ServerPlayer sp) {
                DlcTaskTracker.stripSreWrapper(sp, fakeTask);
                ActiveTaskPayload.clearForPlayer(sp, true);
            }
            mgr.removeFakeTask(player.getUUID());
        }
    }

}
