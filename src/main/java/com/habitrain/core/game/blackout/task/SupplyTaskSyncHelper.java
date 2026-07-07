package com.habitrain.core.game.blackout.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.task.TaskManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 供电池任务（add_coal / repair_wiring / maintain_power）同步完成辅助类。
 *
 * 设计意图（按用户需求）：
 *   任意一个 GOOD 玩家完成某供电池任务时，所有正在做**同一**供电池任务且未完成的
 *   GOOD 玩家也会同步完成并拿到奖励/时间效果。正在做**其它**任务的玩家**完全不动**。
 *
 * 之前的实现（RestorePowerTask.forceAssignMaintainPowerToAllGood）会无差别移除所有
 * GOOD 玩家的当前任务并强制派发 maintain_power，导致玩家在 add_coal/repair_wiring
 * 中途任务"消失"，看起来像任务被自动跳过。
 *
 * 本辅助类只做"同任务同步完成"，不做"任务替换"。任务刷新交给自然刷新机制。
 */
public final class SupplyTaskSyncHelper {

    private SupplyTaskSyncHelper() {}

    /**
     * 同步完成所有 GOOD 玩家中正在做同一供电池任务且未完成的玩家。
     * 不影响正在做其它任务的玩家。
     *
     * @param level         服务端世界
     * @param completerUuid  完成者 UUID（自身不重复处理，已在 onComplete 中拿过奖励）
     * @param fullId        供电池任务的完整 ID（如 habitrain_core:add_coal）
     */
    public static void syncCompletion(ServerLevel level, UUID completerUuid, String fullId) {
        if (level == null || fullId == null) return;
        TaskManager mgr = TaskManager.getInstance();
        List<UUID> alive = BlackoutRoleManager.getAllAlive(level);

        for (UUID uuid : alive) {
            if (uuid.equals(completerUuid)) continue;  // 跳过完成者自身
            if (BlackoutRoleManager.getFaction(level, uuid) != BlackoutRoleManager.Faction.GOOD) continue;

            TaskInstance task = mgr.getActiveTask(uuid);
            // 仅同步同任务（不做变化的玩家这里直接 continue）
            if (task == null || !fullId.equals(task.getFullId())) continue;
            if (task.isFulfilled()) continue;

            ServerPlayer other = level.getServer().getPlayerList().getPlayer(uuid);
            if (other == null) continue;

            // fire onComplete 给奖励 + 时间效果（与原完成者相同路径）
            task.setFulfilled(true);
            try {
                task.getDefinition().onComplete(other, task);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.error(
                        "[SupplyTaskSyncHelper] onComplete failed for synced player {} on task {}",
                        uuid, fullId, t);
            }
            // 完成后由 TaskManager 自然处理（onComplete 通常会调 removeActiveTask 或刷新），
            // 但 SREPlayerTaskComponentMixin 会在 tick 时检测 isFulfilled 并调 handleTaskCompletion，
            // 所以这里不必显式 remove（避免与 mixin 路径重复 fire）。
        }
    }
}