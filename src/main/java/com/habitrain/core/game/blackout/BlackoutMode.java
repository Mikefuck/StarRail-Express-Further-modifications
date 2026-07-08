package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.WinResult;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BlackoutMode implements GameMode {

    public static final String MODE_ID = "habitrains:blackout";
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

    private final Set<String> assignedOncePerGameTasks = new HashSet<>();

    private final BlackoutSyncManager syncManager = new BlackoutSyncManager();
    private final BlackoutVictoryChecker victoryChecker = new BlackoutVictoryChecker(this, syncManager);
    private final BlackoutSheriffResolver sheriffResolver = new BlackoutSheriffResolver();
    private final BlackoutTickCoordinator tickCoordinator =
            new BlackoutTickCoordinator(this, victoryChecker, syncManager, sheriffResolver);

    private static volatile BlackoutRoleManager.Faction lastWinningFaction = null;

    ServerLevel getCurrentLevel() { return currentLevel; }
    boolean isGameEnded() { return gameEnded; }
    void setGameEnded(boolean v) { gameEnded = v; }
    void setPendingEndMessage(String m) { pendingEndMessage = m; }
    void setLastWinningFaction(BlackoutRoleManager.Faction f) { lastWinningFaction = f; }

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
        lastWinningFaction = null;
        assignedOncePerGameTasks.clear();

        BlackoutRoleManager.clear(level);
        BlackoutSheriffVoteManager.reset(level);
        BlackoutTimerSystem.init(level,
                () -> victoryChecker.triggerSREPermanentBlackout(currentLevel),
                () -> victoryChecker.endSREBlackout(currentLevel),
                () -> {});
        BlackoutPoliceHireService.reset(level);
        BlackoutExileVoteManager.reset(level);
        BlackoutShopService.resetRound(level);
        syncManager.onPreStart();
        syncManager.syncReset(level);
        tickCoordinator.onPreStart();
    }

    @Override
    public void onStart(ServerLevel level) {
        ResourceLocation blackoutModeId = ResourceLocation.fromNamespaceAndPath("sre", "blackout");
        var sreMode = SREGameModes.GAME_MODES.get(blackoutModeId);
        if (sreMode == null) {
            HabiTrainCore.LOGGER.error("SREBlackoutGameMode not found!");
            return;
        }
        var sreGame = SREGameWorldComponent.KEY.get(level);
        if (sreGame != null && !sreGame.isRunning()) {
            GameUtils.startGame(level, sreMode,
                    GameConstants.getInTicks(((io.wifi.starrailexpress.api.GameMode) sreMode).defaultStartTime, 0));
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
        String message = pendingEndMessage != null ? pendingEndMessage : "结束对局";
        syncManager.broadcast(level, message);
        pendingEndMessage = null;
        syncManager.syncReset(level);
        currentLevel = null;
    }

    @Override
    public void onCleanup(ServerLevel level) {
        syncManager.syncReset(level);
        BlackoutSheriffVoteManager.reset(level);
        BlackoutTimerSystem.reset(level);
        BlackoutShopService.resetRound(level);
        BlackoutRoleManager.restoreVigilanteRoleMaxes();
        currentLevel = null;
        gameEnded = false;
        pendingEndMessage = null;
    }

    @Override
    public List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
        return tasks.stream()
                .filter(t -> {
                    TaskCategory cat = t.getCategory();
                    return BLACKOUT_GOOD.equals(cat) || BLACKOUT_BAD.equals(cat);
                })
                .filter(t -> {
                    if (ONCE_PER_GAME_TASK_IDS.contains(t.getFullId())
                            && assignedOncePerGameTasks.contains(t.getFullId())) {
                        HabiTrainCore.LOGGER.debug("[Blackout] Excluding once-per-game task {} (already assigned this round)",
                                t.getFullId());
                        return false;
                    }
                    return true;
                })
                .toList();
    }

    public static BlackoutRoleManager.Faction getLastWinningFaction() {
        return lastWinningFaction;
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
}
