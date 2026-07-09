package com.habitrain.core.game.blackout.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.task.TaskManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 去重守卫：防止 syncCompletion 内对每个同步玩家调用 onComplete → onComplete 内调
     * syncCompletion → 循环嵌套递归。
     *
     * 每个需要同步的玩家 UUID 在处理前加入此集合，处理完成后立即移除。
     * 进入 syncCompletion 时先检查自己的 completerUuid 是否已在集合中 → 已在则说明
     * 该 completer 引发的链式递归已进入本条路径，跳过 onComplete 避免重复施加时间影响。
     *
     * 使用 ConcurrentHashMap 而非 ThreadLocal，因为单机集成服务器下主线程与 Netty IO 线程
     * 可能在 hireLock 等同步块中交错执行。
     */
    private static final Set<UUID> syncCompletedPlayers = ConcurrentHashMap.newKeySet();

    private SupplyTaskSyncHelper() {}

    /**
     * 同步完成所有 GOOD 玩家中正在做同一供电池任务且未完成的玩家。
     * 不影响正在做其它任务的玩家。
     *
     * 关键契约：
     * - 时间影响（applyTimeImpact）只由第一个完成者施加一次
     * - 同步完成的玩家只获得状态清理（setFulfilled + onRemove），不额外施加时间影响
     *   （即不调用 onComplete）
     *
     * @param level         服务端世界
     * @param completerUuid  完成者 UUID（自身不重复处理，已在 onComplete 中拿过奖励）
     * @param fullId        供电池任务的完整 ID（如 habitrain_core:add_coal）
     */
    public static void syncCompletion(ServerLevel level, UUID completerUuid, String fullId) {
        if (level == null || fullId == null) return;

        // 去重守卫：如果 completerUuid 已经在集合中，说明本轮同步是链式递归嵌套进来的，
        // 直接返回不做任何事（时间影响/奖励已经在最外层施加过了）
        if (!syncCompletedPlayers.add(completerUuid)) {
            return;
        }
        try {
            doSync(level, fullId);
        } finally {
            syncCompletedPlayers.remove(completerUuid);
        }
    }

    /**
     * 实际同步逻辑，不含去重守卫。
     * 对每个同步玩家只执行 setFulfilled + onRemove 状态清理，不调用 onComplete，
     * 从而避免链式递归施加时间影响。
     */
    private static void doSync(ServerLevel level, String fullId) {
        TaskManager mgr = TaskManager.getInstance();
        List<UUID> alive = BlackoutRoleManager.getAllAlive(level);

        for (UUID uuid : alive) {
            if (BlackoutRoleManager.getFaction(level, uuid) != BlackoutRoleManager.Faction.GOOD) continue;

            TaskInstance task = mgr.getActiveTask(uuid);
            // 仅同步同任务（不做变化的玩家这里直接 continue）
            if (task == null || !fullId.equals(task.getFullId())) continue;
            if (task.isFulfilled()) continue;

            ServerPlayer other = level.getServer().getPlayerList().getPlayer(uuid);
            if (other == null) continue;

            // 标记完成 + 状态清理，但不调用 onComplete（时间影响由 original completer 施加）
            task.setFulfilled(true);
            try {
                task.getDefinition().onRemove(other, task);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.error(
                        "[SupplyTaskSyncHelper] onRemove failed for synced player {} on task {}",
                        uuid, fullId, t);
            }
            // 不调 onComplete — 不施加时间影响，不发放奖励。
            // PerPlayerTaskTicker 在下一 tick 检测 isFulfilled 后调 handleTaskCompletion，
            // 这里不需要显式 removeActiveTask。
        }
    }
}