package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.SREGameLauncher;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.WinResult;
import com.habitrain.core.game.blackout.ExclusiveTaskHudSync;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.TaskManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 停电模式。注册表中是单例，但对局状态按 dimension 隔离，
 * 避免多维度并行开局时互相覆盖 gameEnded / 胜负结果等字段。
 */
public class BlackoutMode implements GameMode {

    public static final String MODE_ID = "habitrain:blackout";
    /** GameModeRegistry 完整 ID：modId + ":" + modeId */
    public static final String REGISTRY_FULL_ID = "habitrain_core:habitrain:blackout";
    public static final String MODE_DISPLAY = "停电模式";

    public static final TaskCategory BLACKOUT_GOOD =
            new TaskCategory("habitrain:blackout_good", "好人任务", MODE_ID);
    public static final TaskCategory BLACKOUT_BAD =
            new TaskCategory("habitrain:blackout_bad", "坏人任务", MODE_ID);

    /**
     * 断线宽限（tick）。宽限期内仍计存活（避免瞬断终局），超时未重连再 eliminate。
     * 设为 0 可恢复「掉线即死」旧行为。
     */
    public static final int DISCONNECT_GRACE_TICKS = 20 * 60;

    /** Per-dimension round state (registry holds one BlackoutMode instance). */
    private static final class RoundState {
        boolean gameEnded;
        String pendingEndMessage;
        WinResult pendingWinResult;
        BlackoutRoleManager.Faction lastWinningFaction;
        /** playerId → gameTime when disconnect was recorded */
        final ConcurrentMap<UUID, Long> offlineSince = new ConcurrentHashMap<>();
        final BlackoutSyncManager syncManager = new BlackoutSyncManager();
        final BlackoutVictoryChecker victoryChecker;
        final BlackoutTickCoordinator tickCoordinator;

        RoundState(BlackoutMode mode) {
            this.victoryChecker = new BlackoutVictoryChecker(mode, syncManager);
            this.tickCoordinator = new BlackoutTickCoordinator(mode, victoryChecker, syncManager);
        }
    }

    private final ConcurrentMap<ResourceKey<Level>, RoundState> rounds = new ConcurrentHashMap<>();
    /** Survives cleanup briefly so finalizeGame fallback can still read winner/result. */
    private final ConcurrentMap<ResourceKey<Level>, BlackoutRoleManager.Faction> finishedWinners = new ConcurrentHashMap<>();
    private final ConcurrentMap<ResourceKey<Level>, WinResult> finishedResults = new ConcurrentHashMap<>();

    /** SRE 游戏启动器 — 通过 setter 注入以解除对 SRE 具体类的编译依赖。 */
    private SREGameLauncher sreGameLauncher;

    private RoundState state(ServerLevel level) {
        return level == null ? null : rounds.get(level.dimension());
    }

    boolean hasRound(ServerLevel level) {
        return level != null && rounds.containsKey(level.dimension());
    }

    /**
     * Whether the given level is the active blackout round.
     * Preferred over the legacy no-arg form for multi-dimension safety.
     */
    public boolean isGameEnded(ServerLevel level) {
        RoundState s = state(level);
        return s != null && s.gameEnded;
    }

    void setGameEnded(ServerLevel level, boolean v) {
        RoundState s = state(level);
        if (s != null) s.gameEnded = v;
    }

    void setPendingEndMessage(ServerLevel level, String m) {
        RoundState s = state(level);
        if (s != null) s.pendingEndMessage = m;
    }

    void setLastWinningFaction(ServerLevel level, BlackoutRoleManager.Faction f) {
        RoundState s = state(level);
        if (s != null) s.lastWinningFaction = f;
        if (level != null && f != null) {
            finishedWinners.put(level.dimension(), f);
        } else if (level != null) {
            finishedWinners.remove(level.dimension());
        }
    }

    void setPendingWinResult(ServerLevel level, WinResult r) {
        RoundState s = state(level);
        if (s != null) s.pendingWinResult = r;
        if (level != null && r != null) {
            finishedResults.put(level.dimension(), r);
        }
    }

    BlackoutVictoryChecker getVictoryChecker(ServerLevel level) {
        RoundState s = state(level);
        return s == null ? null : s.victoryChecker;
    }

    public WinResult getPendingWinResult(ServerLevel level) {
        RoundState s = state(level);
        if (s != null && s.pendingWinResult != null) return s.pendingWinResult;
        return level == null ? null : finishedResults.get(level.dimension());
    }

    public BlackoutRoleManager.Faction getLastWinningFaction(ServerLevel level) {
        RoundState s = state(level);
        if (s != null && s.lastWinningFaction != null) return s.lastWinningFaction;
        return level == null ? null : finishedWinners.get(level.dimension());
    }

    /** 供放逐投票调用：结算后立即检查胜负条件 */
    void checkVictoryAfterExile(ServerLevel level) {
        RoundState s = state(level);
        if (s != null) s.victoryChecker.checkVictory(level);
    }

    @Override
    public String getId() { return MODE_ID; }

    @Override
    public String getDisplayName() { return MODE_DISPLAY; }

    @Override
    public List<TaskCategory> getTaskCategories() { return List.of(BLACKOUT_GOOD, BLACKOUT_BAD); }

    @Override
    public boolean isActive(ServerLevel level) {
        return level != null && rounds.containsKey(level.dimension());
    }

    @Override
    public void onPreStart(ServerLevel level) {
        finishedWinners.remove(level.dimension());
        finishedResults.remove(level.dimension());
        RoundState s = new RoundState(this);
        rounds.put(level.dimension(), s);
        s.gameEnded = false;
        s.pendingEndMessage = null;
        s.pendingWinResult = null;
        s.lastWinningFaction = null;
        s.offlineSince.clear();

        BlackoutRoleManager.clear(level);
        BlackoutTimerSystem.init(level,
                () -> {
                    RoundState rs = state(level);
                    if (rs != null) rs.victoryChecker.triggerSREPermanentBlackout(level);
                },
                () -> {
                    RoundState rs = state(level);
                    if (rs != null) rs.victoryChecker.endSREBlackout(level);
                });
        BlackoutPoliceHireService.reset(level);
        BlackoutExileVoteManager.reset(level);
        BlackoutHornVoteHandler.clear(level);
        com.habitrain.core.game.blackout.shop.BlackoutTaskShopState.reset(level);
        BlackoutPhoneSessionGate.clearAll();
        s.syncManager.onPreStart();
        s.syncManager.syncReset(level);
        s.tickCoordinator.onPreStart();
    }

    @Override
    public void onStart(ServerLevel level) {
        if (sreGameLauncher != null) {
            sreGameLauncher.startBlackoutGame(level);
        }
    }

    @Override
    public void onTick(ServerLevel level) {
        RoundState s = state(level);
        if (s == null) return;
        s.tickCoordinator.tick(level);
    }

    @Override
    public void onTaskComplete(ServerPlayer player, TaskInstance task) {
        if (player == null || task == null) return;
        ServerLevel level = player.serverLevel();
        RoundState s = state(level);
        if (s == null) return;
        TaskCategory cat = task.getDefinition().getCategory();
        if (BLACKOUT_BAD.equals(cat)) {
            onKillerRealTaskComplete(player, task);
        } else if (BLACKOUT_GOOD.equals(cat)) {
            onKillerFakeTaskComplete(player, task);
        }
        s.victoryChecker.checkVictory(level);
    }

    protected void onKillerRealTaskComplete(ServerPlayer player, TaskInstance task) {}
    protected void onKillerFakeTaskComplete(ServerPlayer player, TaskInstance task) {}

    @Override
    public void onPlayerJoin(ServerPlayer player) {
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        RoundState s = state(level);
        if (s == null) return;
        UUID id = player.getUUID();
        // 宽限期内重连：恢复互动，不复活已 eliminate 的玩家
        if (BlackoutRoleManager.isAlive(level, id)) {
            BlackoutRoleManager.clearDisconnected(level, id);
            s.offlineSince.remove(id);
            HabiTrainCore.LOGGER.info("[Blackout] {} reconnected during grace, interactable again",
                    player.getName().getString());
        }
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        RoundState s = state(level);
        if (s == null) return;
        UUID id = player.getUUID();
        BlackoutPhoneSessionGate.clearPlayer(player);
        // 未入存活表（观战/未分配）→ 无需处理
        if (!BlackoutRoleManager.isAlive(level, id)) return;

        if (DISCONNECT_GRACE_TICKS <= 0) {
            // 兼容：宽限 0 = 掉线即死
            BlackoutRoleManager.eliminate(level, id);
            s.offlineSince.remove(id);
            s.victoryChecker.checkVictory(level);
            return;
        }

        // 断线宽限：仍计存活，标记 offline，超时由 tickOfflineGrace 淘汰
        BlackoutRoleManager.markDisconnected(level, id);
        s.offlineSince.put(id, level.getGameTime());
        HabiTrainCore.LOGGER.info("[Blackout] {} disconnected — grace {}s before eliminate",
                player.getName().getString(), DISCONNECT_GRACE_TICKS / 20);
        // 不立即 checkVictory：宽限期内仍计存活
    }

    /**
     * 每秒由 {@link BlackoutTickCoordinator} 调用：超时未重连的 offline 玩家 eliminate 并检查胜负。
     */
    void tickOfflineGrace(ServerLevel level) {
        RoundState s = state(level);
        if (s == null || s.gameEnded || DISCONNECT_GRACE_TICKS <= 0) return;
        if (s.offlineSince.isEmpty()) return;

        long now = level.getGameTime();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : s.offlineSince.entrySet()) {
            long age = now - e.getValue();
            if (age < 0 || age >= DISCONNECT_GRACE_TICKS) {
                expired.add(e.getKey());
            }
        }
        if (expired.isEmpty()) return;

        boolean any = false;
        for (UUID id : expired) {
            s.offlineSince.remove(id);
            if (!BlackoutRoleManager.isAlive(level, id)) continue;
            // 若已在线（异常状态），清除 offline 标记即可
            ServerPlayer online = level.getServer() != null
                    ? level.getServer().getPlayerList().getPlayer(id) : null;
            if (online != null) {
                BlackoutRoleManager.clearDisconnected(level, id);
                continue;
            }
            BlackoutRoleManager.eliminate(level, id);
            any = true;
            HabiTrainCore.LOGGER.info("[Blackout] offline grace expired for {} — eliminated", id);
        }
        if (any) {
            s.victoryChecker.checkVictory(level);
        }
    }

    @Override
    public void onEnd(ServerLevel level, WinResult result) {
        RoundState s = state(level);
        if (s == null) return;
        s.pendingEndMessage = null;
        s.offlineSince.clear();
        s.syncManager.syncReset(level);
    }

    @Override
    public void onCleanup(ServerLevel level) {
        RoundState s = state(level);
        if (s != null) {
            s.syncManager.syncReset(level);
            if (s.pendingWinResult != null) {
                finishedResults.put(level.dimension(), s.pendingWinResult);
            }
            if (s.lastWinningFaction != null) {
                finishedWinners.put(level.dimension(), s.lastWinningFaction);
            }
            s.offlineSince.clear();
        }
        // 对齐 SREGameModeBase：清空自定义任务跟踪，避免 UUID 残留到下一局
        TaskManager.getInstance().clearAllActiveTasks();
        if (level != null) {
            for (ServerPlayer p : level.players()) {
                ActiveTaskPayload.clearForPlayer(p);
                ActiveTaskPayload.clearForPlayer(p, true);
                ExclusiveTaskHudSync.clear(p);
                com.habitrain.core.game.blackout.shop.BlackoutTaskShopService.reclaimTempLantern(p);
                BlackoutPhoneSessionGate.clearPlayer(p);
            }
        }
        BlackoutPoliceHireService.cleanup(level);
        BlackoutExileVoteManager.reset(level);
        BlackoutHornVoteHandler.clear(level);
        BlackoutTimerSystem.reset(level);
        com.habitrain.core.game.blackout.shop.BlackoutTaskShopState.cleanup(level);
        rounds.remove(level.dimension());
    }

    /**
     * 停电模式任务系统独立化：专属任务（BLACKOUT_GOOD / BLACKOUT_BAD）不再自动派发，
     * 仅通过红色电话商店购买或炸毁发电机后强制派发恢复供电。此处将专属任务从自动
     * 派发池中排除，让原版 SRE 任务（吃/喝/外出/修线镜等）正常进入池子。
     */
    @Override
    public List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
        return tasks.stream()
                .filter(t -> {
                    TaskCategory cat = t.getCategory();
                    return !BLACKOUT_GOOD.equals(cat) && !BLACKOUT_BAD.equals(cat);
                })
                .toList();
    }

    /**
     * 设置 SRE 游戏启动器。应在模组初始化时调用一次。
     */
    public void setSreGameLauncher(SREGameLauncher launcher) {
        this.sreGameLauncher = launcher;
    }

    public static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }

    public static void broadcast(ServerLevel level, String message) {
        if (level == null) return;
        var component = net.minecraft.network.chat.Component.literal(message);
        for (ServerPlayer player : level.players()) {
            com.habitrain.core.util.SubtitleNotifier.sendTop(player, net.minecraft.network.chat.Component.empty(), component, 80);
        }
    }

    /**
     * 强制派发恢复供电给所有存活好人（包可见委托，供炸毁发电机路径调用）。
     */
    public void forceAssignRestorePower(ServerLevel level) {
        RoundState s = state(level);
        if (s != null) {
            s.victoryChecker.forceAssignRestorePowerToAllGood(level);
        }
    }
}
