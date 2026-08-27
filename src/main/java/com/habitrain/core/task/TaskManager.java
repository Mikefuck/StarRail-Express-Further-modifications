package com.habitrain.core.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.*;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.blackout.ExclusiveTaskHudSync;
import com.habitrain.core.game.blackout.task.FurnaceExplosionHandler;
import com.habitrain.core.game.sre.DlcTaskTracker;
import com.habitrain.core.network.ActiveTaskPayload;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务管理器 — 取代 HabiTaskManager。
 * 管理活跃自定义任务跟踪、任务完成处理、SRE 集成方法。
 */
public class TaskManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("TaskManager");
    private static volatile TaskManager INSTANCE;

    /** 自定义胜利后缀常量。 */
    public static final String WIN_SUFFIX = "_win";

    /** SRE 游戏状态提供者 — 通过 setter 注入以解除对 SRE 具体类的编译依赖。 */
    private GameStateProvider gameStateProvider;

    private record PendingReclaim(TaskInstance task, boolean fake) {}

    /** Offline players whose tasks were dropped at round end; reclaim on JOIN. */
    private final ConcurrentHashMap<UUID, List<PendingReclaim>> pendingReclaim = new ConcurrentHashMap<>();

    public static TaskManager getInstance() {
        if (INSTANCE == null) {
            synchronized (TaskManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TaskManager();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 设置 SRE 游戏状态提供者。应在模组初始化时调用一次。
     * 解除 TaskManager 对 SRE 具体类的直接编译依赖。
     */
    public void setGameStateProvider(GameStateProvider provider) {
        this.gameStateProvider = provider;
    }

    // ConcurrentHashMap：避免单机模式下 Netty IO 线程编码与主线程修改导致 CME，
    // 以及未来 off-thread 访问的可见性问题。
    private final Map<UUID, TaskInstance> activeCustomTasks = new ConcurrentHashMap<>();

    /**
     * 杀手双任务机制：杀手的"假任务"（并行任务）单独追踪，不覆盖主任务。
     * key = playerUUID, value = 假任务实例（来自好人任务池，完成只给金币不推进胜利）
     */
    private final Map<UUID, TaskInstance> activeFakeTasks = new ConcurrentHashMap<>();

    private final Map<UUID, Boolean> blackoutNextDailyPool = new ConcurrentHashMap<>();

    private final Map<UUID, Map<String, Integer>> dlcTaskCounts = new ConcurrentHashMap<>();

    public int getDlcTaskCount(UUID playerUuid, String fullId) {
        Map<String, Integer> counts = dlcTaskCounts.get(playerUuid);
        return counts == null ? 0 : counts.getOrDefault(fullId, 0);
    }

    public void incrementDlcTaskCount(UUID playerUuid, String fullId) {
        dlcTaskCounts.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                .merge(fullId, 1, Integer::sum);
    }

    public boolean isBlackoutNextDailyPool(UUID playerUuid) {
        return blackoutNextDailyPool.getOrDefault(playerUuid, false);
    }

    public void setBlackoutNextDailyPool(UUID playerUuid, boolean dailyPool) {
        blackoutNextDailyPool.put(playerUuid, dailyPool);
    }

    public void clearBlackoutRotationFlag(UUID playerUuid) {
        blackoutNextDailyPool.remove(playerUuid);
    }

    /**
     * JOIN resync (LifecycleEventsRegistrar) reads these maps and re-sends
     * {@link ActiveTaskPayload} only when a slot is still present. {@link #unbindOwner}
     * does not clear them.
     */
    public TaskInstance getActiveTask(UUID playerUuid) { return activeCustomTasks.get(playerUuid); }
    public void setActiveTask(UUID playerUuid, TaskInstance task) { activeCustomTasks.put(playerUuid, task); }
    public void removeActiveTask(UUID playerUuid) { activeCustomTasks.remove(playerUuid); }

    public TaskInstance getFakeTask(UUID playerUuid) { return activeFakeTasks.get(playerUuid); }
    public void setFakeTask(UUID playerUuid, TaskInstance task) { activeFakeTasks.put(playerUuid, task); }
    public void removeFakeTask(UUID playerUuid) { activeFakeTasks.remove(playerUuid); }

    /**
     * DISCONNECT only: drop {@code Player} entity refs so the instance can outlive
     * the disconnected entity. Does <em>not</em> remove the UUID from
     * {@link #activeCustomTasks} / {@link #activeFakeTasks}.
     * <p>
     * JOIN resync re-sends {@link ActiveTaskPayload} iff {@link #getActiveTask} /
     * {@link #getFakeTask} still return an instance. It does not recreate a slot
     * that upstream {@code task.init()} / PlayerDiscard already cleared, so an
     * ACTIVE reconnect can still find an empty map (F-G9-015 / F-G9-024).
     */
    public void unbindOwner(UUID playerUuid) {
        if (playerUuid == null) return;
        TaskInstance active = activeCustomTasks.get(playerUuid);
        if (active != null) active.unbindOwner();
        TaskInstance fake = activeFakeTasks.get(playerUuid);
        if (fake != null) fake.unbindOwner();
    }

    /** 清空所有玩家的活跃任务（游戏结束时调用） */
    public void clearAllActiveTasks() { activeCustomTasks.clear(); activeFakeTasks.clear(); blackoutNextDailyPool.clear(); dlcTaskCounts.clear(); }

    /** Stop-server / world-swap: clear active tasks and offline reclaim backlog. */
    public void clearAll() {
        clearAllActiveTasks();
        pendingReclaim.clear();
    }

    /**
     * 局终/onCleanup 入口：先 per-player {@code onRemove}+回收，再丢掉该维任务，并清该维炸炉 pending。
     * BlackoutMode.onCleanup 仍可走 {@link #clearActiveTasksForLevel(ResourceKey)}；有 {@link ServerLevel} 时请用本方法。
     */
    public static void clearActiveTasksForLevel(ServerLevel level) {
        if (level == null) return;
        getInstance().clearActiveTasksForLevel(level.dimension(), level.getServer());
    }

    /** 只清空指定维度的活跃任务，避免一个世界结束影响另一个世界的对局。 */
    public void clearActiveTasksForLevel(ResourceKey<Level> dimension) {
        clearActiveTasksForLevel(dimension, GameLifecycleHandler.peekServer());
    }

    private void clearActiveTasksForLevel(ResourceKey<Level> dimension, MinecraftServer server) {
        if (dimension == null) return;
        dropAndReclaim(activeCustomTasks, dimension, server, false);
        dropAndReclaim(activeFakeTasks, dimension, server, true);
        FurnaceExplosionHandler.clearPendingForDimension(dimension);
    }

    private void dropAndReclaim(Map<UUID, TaskInstance> map, ResourceKey<Level> dimension,
                                MinecraftServer server, boolean fake) {
        List<UUID> toDrop = new ArrayList<>();
        for (var e : map.entrySet()) {
            if (dimension.equals(e.getValue().getDimension())) {
                toDrop.add(e.getKey());
            }
        }
        for (UUID id : toDrop) {
            TaskInstance task = map.get(id);
            if (task == null) continue;
            Player player = server != null ? server.getPlayerList().getPlayer(id) : null;
            if (player != null) {
                cancelTrackedTask(player, task, fake);
            } else {
                map.remove(id, task);
                pendingReclaim.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(new PendingReclaim(task, fake));
            }
        }
    }

    /** JOIN：回收局终时玩家不在线而未能扫描的任务道具。 */
    public void flushPendingReclaim(Player player) {
        if (player == null) return;
        List<PendingReclaim> pending = pendingReclaim.remove(player.getUUID());
        if (pending == null || pending.isEmpty()) return;
        for (PendingReclaim entry : pending) {
            cancelTrackedTask(player, entry.task(), entry.fake());
        }
    }

    /**
     * 取消路径：onRemove + 回收道具 + 摘 SRE wrapper + 清 HUD，不发奖。
     * 成功完成不要走这里。
     */
    public void cancelTrackedTask(Player player, TaskInstance instance, boolean fake) {
        if (player == null || instance == null) return;
        UUID id = player.getUUID();
        try {
            instance.getDefinition().onRemove(player, instance);
        } catch (Throwable t) {
            LOGGER.error("onRemove failed: {}", instance.getFullId(), t);
        }
        try {
            ItemReclaimHelper.reclaimForTask(player, instance);
        } catch (Throwable t) {
            LOGGER.error("reclaim failed: {}", instance.getFullId(), t);
        }
        if (player instanceof ServerPlayer sp) {
            DlcTaskTracker.stripSreWrapper(sp, instance);
            ActiveTaskPayload.clearForPlayer(sp, fake);
            if (!fake) {
                ExclusiveTaskHudSync.clear(sp);
            }
        }
        if (fake) {
            if (getFakeTask(id) == instance) {
                removeFakeTask(id);
            }
        } else if (getActiveTask(id) == instance) {
            removeActiveTask(id);
        }
    }

    public void cancelAllTrackedTasks(Player player) {
        if (player == null) return;
        TaskInstance active = getActiveTask(player.getUUID());
        if (active != null) {
            cancelTrackedTask(player, active, false);
        }
        TaskInstance fake = getFakeTask(player.getUUID());
        if (fake != null) {
            cancelTrackedTask(player, fake, true);
        }
    }

    public boolean hasTaskWithId(UUID playerUuid, String fullId) {
        TaskInstance existing = activeCustomTasks.get(playerUuid);
        if (existing != null && existing.getFullId().equals(fullId)) {
            return true;
        }
        TaskInstance fake = activeFakeTasks.get(playerUuid);
        return fake != null && fake.getFullId().equals(fullId);
    }

    // ==================== SRE 集成方法（供 Mixin 使用） ====================

    public String getCurrentMapName(Player player) {
        if (player == null || player.level() == null) return "";
        try {
            AreasWorldComponent areas = AreasWorldComponent.KEY.get(player.level());
            return areas != null && areas.mapName != null ? areas.mapName : "";
        } catch (Exception e) {
            return "";
        }
    }

    public TaskCategory getCurrentGameModeCategory(Player player) {
        if (player == null || player.level() == null) return TaskCategory.ALL;
        try {
            SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(player.level());
            if (gameWorld == null || gameWorld.getGameMode() == null) return TaskCategory.ALL;
            String modeId = gameWorld.getGameMode().identifier.toString();
            if (modeId.contains("repair_escape") || modeId.contains("repair")) {
                return TaskCategory.REPAIR;
            }
            if (modeId.contains("murder")) {
                return TaskCategory.MURDER;
            }
            return TaskCategory.ALL;
        } catch (Exception e) {
            return TaskCategory.ALL;
        }
    }

    public boolean isOriginalTaskDisabled(String taskName, String mapName) {
        String fullId = "habitrain_core:" + taskName.toLowerCase();
        // 与 TaskPoolBuilder / 配置 UI 同一条 isTaskEnabled 语义
        return !ConfigManager.getInstance().isTaskEnabled(fullId, mapName);
    }

    // ==================== 任务完成处理 ====================

    public void handleTaskCompletion(ServerPlayer player, TaskInstance instance) {
        TaskDefinition def = instance.getDefinition();

        // 先移除活跃任务，避免 DLC 在 onTaskComplete 回调内连发新任务被随后清除。
        // 用 == instance 守卫，避免删掉回调里新分配的任务。
        if (getActiveTask(player.getUUID()) == instance) {
            removeActiveTask(player.getUUID());
        }

        // 统一桥接 SRE 角色任务结算与奖励（警卫获取左轮、其他角色道具/技能触发、金币、心情、连击等）
        bridgeSreTaskCompletion(player, instance);

        if (player.level() instanceof ServerLevel sl) {
            GameModeRegistry.getActiveForLevel(sl).ifPresent(gm ->
                gm.onTaskComplete(player, instance));
        }

        // 默剧杀手：habitrain TaskManager 完成的任务也累计狂暴折扣
        // （SRE 原版任务走 RoleMethodDispatcherMixin；此处覆盖本 mod 任务路径，避免漏计）
        try {
            if (com.habitrain.core.game.sre.role.HabiRoles.isHabiRole(
                    player, com.habitrain.core.game.sre.role.HabiRoles.MIME_KILLER)) {
                com.habitrain.core.game.sre.role.component.MimeKillerComponent.KEY
                        .maybeGet(player)
                        .ifPresent(com.habitrain.core.game.sre.role.component.MimeKillerComponent::onTaskComplete);
            }
        } catch (Throwable t) {
            LOGGER.debug("MimeKiller task discount apply failed", t);
        }

        // 谦卑：自定义任务完成时附近玩家 actionbar「谢谢」
        try {
            com.habitrain.core.game.sre.modifier.virtue.HumilityVirtue.onTaskComplete(player);
        } catch (Throwable t) {
            LOGGER.debug("Humility onTaskComplete failed", t);
        }

        if (def.canDirectlyWin()) {
            triggerDirectWin(player, instance);
        }
    }

    /**
     * 桥接 API 任务完成至 SRE 原版与扩展管道：
     * 1. 触发 RoleMethodDispatcher.callOnFinishQuest -> role.onFinishQuest -> giveGeneralTaskAwards
     *    （警卫累积 2 个任务获枪、蛋糕师得食材、肉丸加赏金、以及所有角色自定义任务奖励）
     * 2. 发放心情奖励与客户端完成音效报文（TaskCompletePayload）
     * 3. 递增并同步连击计数（taskStreak）
     * 4. 进食任务触发大胃王词条（onBigEaterTaskComplete）
     * 5. 附近玩家狂躁症 / 渡鸦联动通知
     */
    private void bridgeSreTaskCompletion(ServerPlayer player, TaskInstance instance) {
        try {
            SREPlayerTaskComponent taskComp = SREPlayerTaskComponent.KEY.maybeGet(player).orElse(null);
            int streak = taskComp != null ? taskComp.taskStreak : 0;
            boolean parallel = taskComp != null && taskComp.parallelTaskGenerated;

            // 1. 调用 RoleMethodDispatcher.callOnFinishQuest
            String questName = instance.getDefinition().getTaskId();
            io.wifi.starrailexpress.api.RoleMethodDispatcher.callOnFinishQuest(player, questName, streak, parallel);

            // 2. 发放情绪奖励（若未在配置中指定自定义情绪奖励）
            com.habitrain.core.config.TaskConfigEntry config = ConfigManager.getInstance().getTaskConfig(instance.getFullId());
            if (config == null || !config.hasEmotionReward) {
                SREPlayerMoodComponent.KEY.maybeGet(player).ifPresent(mood -> {
                    float moodGain = io.wifi.starrailexpress.game.GameConstants.MOOD_GAIN;
                    if (parallel) {
                        moodGain += io.wifi.starrailexpress.game.GameConstants.PARALLEL_TASK_COMPLETION_BONUS;
                    }
                    mood.addMood(moodGain);
                });
            }

            // 3. 发送任务完成音效与动画数据包
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                    player, new io.wifi.starrailexpress.network.original.TaskCompletePayload());

            // 4. 增加连击计数
            if (taskComp != null) {
                taskComp.taskStreak++;
                taskComp.sync();
            }

            // 5. 大胃王词条：完成进食任务时额外恢复理智与金币
            if (HabiTrainCore.TASK_EAT.equals(instance.getFullId()) || "eat".equalsIgnoreCase(questName)) {
                try {
                    org.agmas.noellesroles.role.ModifierEffects.onBigEaterTaskComplete(player);
                } catch (Throwable t) {
                    LOGGER.debug("BigEater onTaskComplete failed", t);
                }
            }

            // 6. 触发附近任务完成联动（狂躁症、渡鸦等）
            notifyNearbyTaskComplete(player);

        } catch (Throwable t) {
            LOGGER.error("bridgeSreTaskCompletion failed for task {}", instance.getFullId(), t);
        }
    }

    private static void notifyNearbyTaskComplete(ServerPlayer completingPlayer) {
        try {
            var worldModifiers = org.agmas.harpymodloader.component.WorldModifierComponent.KEY.get(completingPlayer.level());
            if (worldModifiers != null) {
                for (Player nearby : completingPlayer.level().players()) {
                    if (nearby != completingPlayer && nearby.distanceTo(completingPlayer) <= 11.0
                            && nearby instanceof ServerPlayer nearbySp
                            && io.wifi.starrailexpress.game.GameUtils.isPlayerAliveAndSurvival(nearbySp)
                            && worldModifiers.isModifier(nearbySp.getUUID(), org.agmas.noellesroles.role.TraitorAndModifiers.MANIC)) {
                        org.agmas.noellesroles.role.ModifierEffects.onNearbyTaskComplete(nearbySp, completingPlayer);
                    }
                }
            }
            SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(completingPlayer.level());
            if (gameWorld != null) {
                for (Player nearby : completingPlayer.level().players()) {
                    if (nearby != completingPlayer
                            && nearby.distanceToSqr(completingPlayer) <= org.agmas.noellesroles.game.roles.neutral.raven.RavenPlayerComponent.CHARGE_RADIUS
                                    * org.agmas.noellesroles.game.roles.neutral.raven.RavenPlayerComponent.CHARGE_RADIUS
                            && nearby instanceof ServerPlayer nearbySp
                            && io.wifi.starrailexpress.game.GameUtils.isPlayerAliveAndSurvival(nearbySp)
                            && gameWorld.isRole(nearbySp, org.agmas.noellesroles.role.ModRoles.RAVEN)) {
                        org.agmas.noellesroles.component.ModComponents.RAVEN.get(nearbySp).onNearbyTaskComplete();
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("notifyNearbyTaskComplete failed", t);
        }
    }

    private void triggerDirectWin(ServerPlayer player, TaskInstance instance) {
        try {
            if (!(player.level() instanceof ServerLevel sl)) return;
            if (!isSreGameActive(sl)) {
                LOGGER.debug("Refuse custom win {}: game is not ACTIVE", instance.getFullId());
                return;
            }
            String winnerId = instance.getDefinition().getModId()
                    + "_" + instance.getDefinition().getTaskId() + WIN_SUFFIX;
            if (gameStateProvider != null) {
                gameStateProvider.triggerCustomWin(sl, winnerId, player.getUUID());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to trigger direct win: " + instance.getFullId(), e);
        }
    }

    private static boolean isSreGameActive(ServerLevel level) {
        try {
            SREGameWorldComponent gw = SREGameWorldComponent.KEY.get(level);
            return gw != null && gw.getGameStatus() == SREGameWorldComponent.GameStatus.ACTIVE;
        } catch (Throwable t) {
            return false;
        }
    }
}
