package com.habitrain.core.game.sre;

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
        // 维修人员不参与对局：不 tick/不派发任何 api 任务（双保险，SRE 本就不给其开局名单）
        if (RepairModeManager.isRepairer(player)) {
            return;
        }
        TaskManager mgr = TaskManager.getInstance();
        // 上游可能在上一次 serverTick / 指令 / 方块交互里直接把 wrapper 从 SRE tasks 摘掉
        // （并列任务互相顶掉、清空任务等）。先对账再 tick，避免幽灵任务继续推进/发奖、
        // 旧任务点继续透视、以及 DLC 任务池被 active 守卫永久挡住不再刷新。
        if (player instanceof ServerPlayer reconcileTarget) {
            DlcTaskTracker.reconcileDroppedTasks(reconcileTarget);
        }
        TaskInstance customTask = mgr.getActiveTask(player.getUUID());
        if (customTask == null) {
            DlcTaskTracker.forgetObserved(player.getUUID());
            return;
        }
        if (!canTickCustomTasks(player)) {
            // 非 ACTIVE / 死亡 / 休息区 / 旁观 / 创造：回收并卸任务，避免 STOPPING 改胜负。
            mgr.cancelAllTrackedTasks(player);
            return;
        }

        customTask.tick(player);
        if (customTask.isFulfilled()) {
            handleMainTaskDone(mgr, customTask, player);
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
        if (customTask.isFailed()) {
            LOGGER.debug("[HabiDebug] Custom task {} failed, removing tracking without completion reward",
                    customTask.getFullId());
            mgr.cancelTrackedTask(player, customTask);
        } else {
            LOGGER.debug("[HabiDebug] Custom task {} fulfilled, removing tracking", customTask.getFullId());
            if (player instanceof ServerPlayer sp) {
                // 先摘 SRE wrapper，避免本方法返回后上游 serverTick 再 callOnFinishQuest。
                DlcTaskTracker.stripSreWrapper(sp, customTask);
                mgr.handleTaskCompletion(sp, customTask);
                ActiveTaskPayload.clearForPlayer(sp);
                // 并列任务状态下顺带让"另一个任务"消失（上游同样语义），
                // 否则它会一直占住刷新槽位，玩家表现为"还有一个任务、而且不给刷新"。
                DlcTaskTracker.dismissParallelSiblings(sp);
            }
        }
    }
}
