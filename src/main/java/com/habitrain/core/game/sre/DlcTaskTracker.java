package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DlcTaskTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("DlcTaskTracker");

    /**
     * 本局任务跟踪记录，用于区分「被上游从 tasks 摘除」和「刚分配还没进 tasks / 玩家刚重连」。
     * {@link #observed} 记录确实在 SRE {@code tasks} 里见过的实例，
     * {@link #createdTick} 记录实例创建时的服务端 tick。仅在服务端线程访问。
     */
    private static final class TrackState {
        final Set<TaskInstance> observed = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<TaskInstance, Integer> createdTick = new IdentityHashMap<>();
    }

    private static final Map<UUID, TrackState> TRACK_STATES = new ConcurrentHashMap<>();

    private DlcTaskTracker() {}

    private static TrackState state(UUID playerId) {
        return TRACK_STATES.computeIfAbsent(playerId, k -> new TrackState());
    }

    private static int serverTickOf(Player player) {
        try {
            return player.getServer() != null ? player.getServer().getTickCount() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * JOIN 时重置跟踪记录：重连后上游 {@code tasks} 可能还没重建，
     * 不能因此把 TaskManager 里刻意保留的任务（F-G9-015 / F-G9-024）误判成被摘除。
     */
    public static void onPlayerJoin(Player player) {
        if (player == null) return;
        TRACK_STATES.remove(player.getUUID());
    }

    /** 玩家已无任何被跟踪任务时丢弃记录，避免跨局残留。 */
    public static void forgetObserved(UUID playerId) {
        if (playerId != null) TRACK_STATES.remove(playerId);
    }

    /**
     * Creates a DLC task instance, tracks assignment counts,
     * and returns the wrapped TrainTask.
     *
     * @param def    the DLC task definition
     * @param player the player
     * @return wrapped TrainTask
     */
    public static SREPlayerTaskComponent.TrainTask createAndTrackDlcTask(
            TaskDefinition def, Player player) {
        TaskManager mgr = TaskManager.getInstance();
        LOGGER.debug("[HabiDebug] createAndTrackDlcTask: {} for {}",
                def.getFullId(), player.getName().getString());
        TaskInstance instance = new TaskInstance(def);
        instance.setDimension(player.level().dimension());
        def.onAssign(player, instance);
        state(player.getUUID()).createdTick.put(instance, serverTickOf(player));

        mgr.incrementDlcTaskCount(player.getUUID(), def.getFullId());
        mgr.setActiveTask(player.getUUID(), instance);
        if (player instanceof ServerPlayer sp) {
            ActiveTaskPayload.sendToPlayer(sp, def.getFullId());
            sendNewTaskTop(sp, def);
        }
        return new SRETrainTaskWrapper(instance);
    }

    /**
     * 从 SRE {@code tasks} map 摘掉包装了该 {@link TaskInstance} 的 wrapper。
     * 必须在 HEAD ticker 返回前调用，避免上游 {@code serverTick} 再 {@code callOnFinishQuest}。
     */
    public static void stripSreWrapper(ServerPlayer player, TaskInstance instance) {
        if (player == null || instance == null) return;
        try {
            SREPlayerTaskComponent comp = SREPlayerTaskComponent.KEY.get(player);
            if (comp == null || comp.tasks == null || comp.tasks.isEmpty()) return;
            boolean removed = false;
            var it = comp.tasks.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                if (entry.getValue() instanceof SRETrainTaskWrapper wrapper
                        && wrapper.unwrap() == instance) {
                    it.remove();
                    removed = true;
                }
            }
            if (removed) {
                comp.sync();
            }
        } catch (Throwable t) {
            LOGGER.debug("stripSreWrapper failed for {}", instance.getFullId(), t);
        }
    }

    /**
     * 对账：把「已被上游摘掉 wrapper、却还留在 TaskManager 里」的 DLC 任务清理掉。
     *
     * <p>上游 {@code SREPlayerTaskComponent.serverTick} 与若干指令 / 方块交互 / 料理道具
     * 会直接改 {@code comp.tasks}（并列任务「完成一个→另一个消失」、{@code tmm:game tasks}
     * 的清除、{@code CHANGE_TASK}/{@code CLEAR_TASKS}、场景方块等），全部绕过 TaskManager。
     * 一旦 wrapper 被这样摘除而 TaskManager 不清理，就会留下"幽灵任务"：
     * <ul>
     *   <li>它仍被 {@link PerPlayerTaskTicker} 每 tick 推进，可能自行完成并静默发奖（心情/连击/职业奖励）；</li>
     *   <li>客户端 {@link ActiveTaskPayload} 永不清空 → 旧任务方块/吃喝任务点持续透视；</li>
     *   <li>{@code DlcTaskPoolBuilder} 的 active 守卫一直被占住 → 之后只刷原版任务、DLC 任务不再刷新；</li>
     *   <li>{@code hasTaskWithId} 也永久挡住同名任务。</li>
     * </ul>
     *
     * <p>调用时机为 SRE {@code serverTick} 的 HEAD（tick 之前）：此时上一 tick、
     * 指令、方块交互造成的 {@code tasks} 改动都已生效，而本 tick 的新任务尚未生成，
     * 因此「仍被跟踪、但 wrapper 不在 {@code tasks} 中」必然是上游把它摘掉了。
     */
    public static void reconcileDroppedTasks(ServerPlayer player) {
        if (player == null) return;
        SREPlayerTaskComponent comp;
        try {
            comp = SREPlayerTaskComponent.KEY.maybeGet(player).orElse(null);
        } catch (Throwable t) {
            return;
        }
        if (comp == null) return;
        reconcileActive(player, comp);
    }

    private static void reconcileActive(ServerPlayer player, SREPlayerTaskComponent comp) {
        TaskManager mgr = TaskManager.getInstance();
        UUID id = player.getUUID();
        TaskInstance tracked = mgr.getActiveTask(id);
        if (tracked == null) return;

        TrackState st = TRACK_STATES.get(id);
        if (hasLiveWrapper(comp, tracked)) {
            if (st != null) st.observed.add(tracked);
            return;
        }
        // 本局没有该实例的任何记录：属于「玩家重连、上游 tasks 还没重建但 TaskManager 仍保留」
        // （F-G9-015 / F-G9-024 刻意保留的 JOIN resync 行为），不做清理。
        if (st == null) return;
        boolean seenAlive = st.observed.contains(tracked);
        Integer createdTick = st.createdTick.get(tracked);
        // 创建于本 tick 的任务可能还没被上游 put 进 tasks，给它一个 tick 的宽限；
        // 下一个 tick 仍不在 tasks 里，就一定是分配后立刻被摘掉（如并列任务同 tick 互顶）。
        boolean settled = createdTick != null && serverTickOf(player) > createdTick;
        if (!seenAlive && !settled) return;

        st.observed.remove(tracked);
        st.createdTick.remove(tracked);

        LOGGER.info("[DlcTaskTracker] 任务 {} 已被上游从 tasks 摘除，清理跟踪并通知客户端清空 ActiveTask",
                tracked.getFullId());
        // 与取消路径一致：onRemove + 回收道具 + 摘 SRE wrapper + 清客户端 HUD/透视，不发奖。
        mgr.cancelTrackedTask(player, tracked);
    }

    /** wrapper 是否仍挂在 {@code comp.tasks} 上（按实例同一性比较）。 */
    private static boolean hasLiveWrapper(SREPlayerTaskComponent comp, TaskInstance instance) {
        var tasks = comp.tasks;
        if (tasks == null || tasks.isEmpty()) return false;
        for (var task : tasks.values()) {
            if (task instanceof SRETrainTaskWrapper wrapper && wrapper.unwrap() == instance) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对齐上游 {@code serverTick} 的并列任务语义：「完成其中一个任务时，另一个任务自动消失（不发奖）」。
     *
     * <p>我们的 DLC 任务在 {@code serverTick} 的 HEAD 就被摘掉 wrapper 并结算完成，
     * 上游那一次 {@code removals} 因此为空，永远不会进入它的 {@code dismissed} 分支。
     * 结果：玩家在"并列任务"状态下完成 DLC 任务后，另一个原版任务会一直挂在左上角、
     * 既不消失也占住刷新槽位（{@code generateTask()} 在 {@code tasks} 非空时返回 null，
     * 每次都白烧一次 nextTaskTimer），表现就是"做完了还有一个任务、而且不给刷新"。
     */
    public static void dismissParallelSiblings(ServerPlayer player) {
        if (player == null) return;
        SREPlayerTaskComponent comp;
        try {
            comp = SREPlayerTaskComponent.KEY.maybeGet(player).orElse(null);
        } catch (Throwable t) {
            return;
        }
        if (comp == null || comp.tasks == null || comp.tasks.isEmpty()) return;
        if (!comp.parallelTaskGenerated) return;

        List<SREPlayerTaskComponent.Task> dropped = new ArrayList<>(comp.tasks.keySet());
        for (SREPlayerTaskComponent.Task type : dropped) {
            comp.tasks.remove(type);
            comp.parallelTaskTypes.remove(type);
            clearSceneTask(player, type);
        }
        comp.parallelTaskTypes.clear();
        comp.currentTaskAge = 0;
        comp.parallelTaskGenerated = false;
        comp.sync();
        LOGGER.info("[DlcTaskTracker] 完成并列任务之一，已让另外 {} 个任务消失（不发奖），刷新槽位已释放",
                dropped.size());
    }

    /** 与上游 {@code clearSceneTask} 等价：场景任务被移除时解除 SceneTaskManager 注册。 */
    private static void clearSceneTask(ServerPlayer player, SREPlayerTaskComponent.Task type) {
        if (type == null) return;
        org.agmas.noellesroles.scene.SceneTaskManager.Type sceneType = switch (type) {
            case LIGHT_STOVE -> org.agmas.noellesroles.scene.SceneTaskManager.Type.LIGHT_STOVE;
            case CLEAN_DUST -> org.agmas.noellesroles.scene.SceneTaskManager.Type.CLEAN_DUST;
            case TRANSPORT -> org.agmas.noellesroles.scene.SceneTaskManager.Type.TRANSPORT;
            case PRAY -> org.agmas.noellesroles.scene.SceneTaskManager.Type.PRAY;
            case PRUNE_BUSH -> org.agmas.noellesroles.scene.SceneTaskManager.Type.PRUNE_BUSH;
            case HARVEST_CROP -> org.agmas.noellesroles.scene.SceneTaskManager.Type.HARVEST_CROP;
            default -> null;
        };
        if (sceneType != null) {
            org.agmas.noellesroles.scene.SceneTaskManager.clear(player, sceneType);
        }
    }

    /** 对齐 SRE 原版 subtitle.task.new 的 TOP 报幕，避免只闪中间 ActiveTask。 */
    private static void sendNewTaskTop(ServerPlayer player, TaskDefinition def) {
        String name = def.getDisplayName();
        Component title = Component.literal(name != null && !name.isEmpty() ? name : def.getFullId());
        Component sub = Component.translatable("subtitle.task.new");
        SubtitleNotifier.sendTop(player, title, sub, 75);
    }
}
