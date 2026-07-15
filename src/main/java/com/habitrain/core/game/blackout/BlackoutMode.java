package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.SREGameLauncher;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.WinResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BlackoutMode implements GameMode {

    public static final String MODE_ID = "habitrain:blackout";
    /** GameModeRegistry 完整 ID：modId + ":" + modeId */
    public static final String REGISTRY_FULL_ID = "habitrain_core:habitrain:blackout";
    public static final String MODE_DISPLAY = "停电模式";

    public static final TaskCategory BLACKOUT_GOOD =
            new TaskCategory("habitrain:blackout_good", "好人任务", MODE_ID);
    public static final TaskCategory BLACKOUT_BAD =
            new TaskCategory("habitrain:blackout_bad", "坏人任务", MODE_ID);

    public static final Set<String> ONCE_PER_GAME_TASK_IDS =
            Collections.unmodifiableSet(new HashSet<>(List.of("habitrain_core:furnace_explosion")));

    private ServerLevel currentLevel;
    private boolean gameEnded = false;
    private String pendingEndMessage = null;
    /** 供 finalize 延后 GameModeRegistry.stop 使用的结算结果。 */
    private WinResult pendingWinResult = null;

    private final Set<String> assignedOncePerGameTasks = new HashSet<>();

    private final BlackoutSyncManager syncManager = new BlackoutSyncManager();
    private final BlackoutVictoryChecker victoryChecker = new BlackoutVictoryChecker(this, syncManager);
    private final BlackoutTickCoordinator tickCoordinator =
            new BlackoutTickCoordinator(this, victoryChecker, syncManager);

    private BlackoutRoleManager.Faction lastWinningFaction = null;

    /** SRE 游戏启动器 — 通过 setter 注入以解除对 SRE 具体类的编译依赖。 */
    private SREGameLauncher sreGameLauncher;

    ServerLevel getCurrentLevel() { return currentLevel; }
    public boolean isGameEnded() { return gameEnded; }
    void setGameEnded(boolean v) { gameEnded = v; }
    void setPendingEndMessage(String m) { pendingEndMessage = m; }
    void setLastWinningFaction(BlackoutRoleManager.Faction f) { lastWinningFaction = f; }
    void setPendingWinResult(WinResult r) { pendingWinResult = r; }

    BlackoutVictoryChecker getVictoryChecker() { return victoryChecker; }

    public WinResult getPendingWinResult() { return pendingWinResult; }

    /** 供放逐投票调用：结算后立即检查胜负条件 */
    void checkVictoryAfterExile(ServerLevel level) {
        victoryChecker.checkVictory(level);
    }

    @Override
    public String getId() { return MODE_ID; }

    @Override
    public String getDisplayName() { return MODE_DISPLAY; }

    @Override
    public List<TaskCategory> getTaskCategories() { return List.of(BLACKOUT_GOOD, BLACKOUT_BAD); }

    @Override
    public boolean isActive(ServerLevel level) {
        return currentLevel != null && currentLevel.dimension().equals(level.dimension());
    }

    @Override
    public void onPreStart(ServerLevel level) {
        currentLevel = level;
        gameEnded = false;
        pendingEndMessage = null;
        pendingWinResult = null;
        lastWinningFaction = null;
        assignedOncePerGameTasks.clear();

        BlackoutRoleManager.clear(level);
        BlackoutSheriffVoteManager.reset(level);
        BlackoutTimerSystem.init(level,
                () -> victoryChecker.triggerSREPermanentBlackout(currentLevel),
                () -> victoryChecker.endSREBlackout(currentLevel));
        BlackoutPoliceHireService.reset(level);
        BlackoutExileVoteManager.reset(level);
        BlackoutHornVoteHandler.clearAll();
        BlackoutShopService.resetRound(level);
        com.habitrain.core.game.blackout.shop.BlackoutTaskShopState.reset(level);
        syncManager.onPreStart();
        syncManager.syncReset(level);
        tickCoordinator.onPreStart();
    }

    @Override
    public void onStart(ServerLevel level) {
        if (sreGameLauncher != null) {
            sreGameLauncher.startBlackoutGame(level);
        }
    }

    @Override
    public void onTick(ServerLevel level) {
        tickCoordinator.tick(level);
    }

    @Override
    public void onTaskComplete(ServerPlayer player, TaskInstance task) {
        if (currentLevel != null && player != null && task != null) {
            TaskCategory cat = task.getDefinition().getCategory();
            if (BLACKOUT_BAD.equals(cat)) {
                onKillerRealTaskComplete(player, task);
            } else if (BLACKOUT_GOOD.equals(cat)) {
                onKillerFakeTaskComplete(player, task);
            }
        }
        victoryChecker.checkVictory(currentLevel);
    }

    @Override
    public void onTaskAssign(ServerPlayer player, TaskInstance task) {
        if (task != null && ONCE_PER_GAME_TASK_IDS.contains(task.getFullId())) {
            assignedOncePerGameTasks.add(task.getFullId());
            HabiTrainCore.LOGGER.info("[Blackout] Once-per-game task {} assigned to {}, will not reassign this round",
                    task.getFullId(), player.getName().getString());
        }
    }

    protected void onKillerRealTaskComplete(ServerPlayer player, TaskInstance task) {}
    protected void onKillerFakeTaskComplete(ServerPlayer player, TaskInstance task) {}

    @Override
    public void onPlayerJoin(ServerPlayer player) {
        BlackoutSheriffVoteManager.onPlayerJoined(currentLevel, player);
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        BlackoutRoleManager.eliminate(currentLevel, player.getUUID());
        victoryChecker.checkVictory(currentLevel);
    }

    @Override
    public void onEnd(ServerLevel level, WinResult result) {
        // 胜利 TOP 已在 endGame 取消；仅清理 pending，不补发字幕。
        pendingEndMessage = null;
        syncManager.syncReset(level);
        currentLevel = null;
    }

    @Override
    public void onCleanup(ServerLevel level) {
        syncManager.syncReset(level);
        BlackoutSheriffVoteManager.reset(level);
        BlackoutPoliceHireService.cleanup(level);
        BlackoutExileVoteManager.reset(level);
        BlackoutHornVoteHandler.clearAll();
        BlackoutTimerSystem.reset(level);
        BlackoutShopService.resetRound(level);
        com.habitrain.core.game.blackout.shop.BlackoutTaskShopState.cleanup(level);
        // 局末回收所有在线玩家的临时电源提灯，防跨局残留
        for (ServerPlayer p : level.players()) {
            com.habitrain.core.game.blackout.shop.BlackoutTaskShopService.reclaimTempLantern(p);
        }
        BlackoutRoleManager.restoreVigilanteRoleMaxes();
        // roleHistory / factionHistory 保留到下一局 onPreStart 的 clear，
        // 以便 SRE finalize 阶段仍可读取结算身份。
        currentLevel = null;
        gameEnded = false;
        pendingEndMessage = null;
        pendingWinResult = null;
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
                    // 排除停电专属任务（电话购买/强制派发，不走自动池）
                    return !BLACKOUT_GOOD.equals(cat) && !BLACKOUT_BAD.equals(cat);
                })
                .toList();
    }

    public BlackoutRoleManager.Faction getLastWinningFaction() {
        return lastWinningFaction;
    }

    /**
     * 设置 SRE 游戏启动器。应在模组初始化时调用一次。
     * 解除 BlackoutMode 对 SRE 具体类的直接编译依赖。
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
     * 仅当当前激活的模式是本 BlackoutMode 实例时生效。
     */
    public void forceAssignRestorePower(ServerLevel level) {
        if (currentLevel != null && currentLevel.dimension().equals(level.dimension())) {
            victoryChecker.forceAssignRestorePowerToAllGood(level);
        }
    }
}
