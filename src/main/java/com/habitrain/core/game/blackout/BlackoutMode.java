package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.api.WinResult;
import com.habitrain.core.game.blackout.BlackoutSheriffVoteManager.VoteResolution;
import com.habitrain.core.network.BlackoutAnnouncePayload;
import com.habitrain.core.network.BlackoutTimerPayload;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BlackoutMode implements GameMode {

    public static final String MODE_ID = "habitrains:blackout";
    public static final String MODE_DISPLAY = "\u505c\u7535\u6a21\u5f0f";

    /**
     * 好人任务池 —— 停电模式中好人阵营(GOOD)玩家的任务池。
     * <p>新增好人任务时，注册用 {@code .category(BlackoutMode.BLACKOUT_GOOD)} 归属此池。
     * 当前任务：添煤 / 修理线路 / 维持供电。
     */
    public static final TaskCategory BLACKOUT_GOOD =
            new TaskCategory("habitrain:blackout_good", "\u597d\u4eba\u4efb\u52a1", MODE_ID);
    /**
     * 坏人任务池 —— 停电模式中坏人阵营(BAD)玩家的任务池（杀手真任务）。
     * <p>新增坏人任务时，注册用 {@code .category(BlackoutMode.BLACKOUT_BAD)} 归属此池。
     * 当前任务：破坏线路 / 炸毁熔炉。
     */
    public static final TaskCategory BLACKOUT_BAD =
            new TaskCategory("habitrain:blackout_bad", "\u574f\u4eba\u4efb\u52a1", MODE_ID);

    /**
     * 每局只能出现一次的任务 ID 集合。
     * 这些任务一旦被分配给任意玩家，本局内不再进入任务池。
     * 当前：炸毁熔炉
     */
    public static final Set<String> ONCE_PER_GAME_TASK_IDS =
            Collections.unmodifiableSet(new HashSet<>(List.of("habitrain_core:furnace_explosion")));

    private ServerLevel currentLevel;
    private int tickAccumulator = 0;
    private boolean gameEnded = false;
    private boolean sreGameRunning = false;
    private String pendingEndMessage = null;

    /**
     * 本局已经分配过的"每局一次"任务 ID 集合。
     * 在 {@link #onPreStart} 中清空，在 {@link #onTaskAssign} 中标记，
     * 在 {@link #filterAvailableTasks} 中过滤掉。
     */
    private final Set<String> assignedOncePerGameTasks = new HashSet<>();

    /**
     * 上一局结束时的获胜阵营，供 {@link com.habitrain.core.game.blackout.sre.SREBlackoutGameMode#finalizeGame}
     * 在 SRE 回放数据生成后覆盖 winStatus / 单人 hasWin 使用。
     * <p>
     * null 表示未结算或同归于尽。在 {@link #endGame} 中赋值，{@link #onPreStart} 中清空。
     */
    private static volatile BlackoutRoleManager.Faction lastWinningFaction = null;

    @Override
    public String getId() {
        return MODE_ID;
    }

    @Override
    public String getDisplayName() {
        return MODE_DISPLAY;
    }

    @Override
    public List<TaskCategory> getTaskCategories() {
        return List.of(BLACKOUT_GOOD, BLACKOUT_BAD);
    }

    @Override
    public boolean isActive(ServerLevel level) {
        return currentLevel != null && currentLevel.dimension().equals(level.dimension());
    }

    @Override
    public void onPreStart(ServerLevel level) {
        currentLevel = level;
        tickAccumulator = 0;
        gameEnded = false;
        sreGameRunning = false;
        pendingEndMessage = null;
        lastWinningFaction = null;
        assignedOncePerGameTasks.clear();

        BlackoutRoleManager.clear(level);
        BlackoutSheriffVoteManager.reset(level);
        BlackoutTimerSystem.init(level, this::triggerSREPermanentBlackout, this::endSREBlackout, this::sendTimeWarning);
        BlackoutShopService.resetRound(level);
        syncClientBlackoutReset(level);
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
        if (level != currentLevel || gameEnded) return;

        var sreGame = SREGameWorldComponent.KEY.get(level);
        boolean sreActive = sreGame != null && sreGame.isRunning();

        if (sreActive && !sreGameRunning) {
            sreGameRunning = true;
        }

        if (!sreActive && sreGameRunning) {
            sreGameRunning = false;
            endGame("\u6e38\u620f\u7ed3\u675f");
            return;
        }

        if (!sreActive) return;

        tickAccumulator++;
        if (tickAccumulator % 20 == 0) {
            BlackoutTimerSystem.tickSecond(currentLevel);

            // 每秒把真实计时值同步给客户端 HUD（进度条右侧倒计时需要实时数据）
            var phase = BlackoutTimerSystem.getPhase(currentLevel);
            int countdown = switch (phase) {
                case NORMAL -> BlackoutTimerSystem.getBlackoutCountdown(currentLevel);
                case MAINTENANCE -> BlackoutTimerSystem.getMaintenanceTime(currentLevel);
                default -> 0;
            };
            if (currentLevel.getServer() != null) {
                BlackoutTimerPayload.broadcastToAll(currentLevel.getServer(),
                        BlackoutTimerSystem.getTotalTimeRemaining(currentLevel),
                        countdown,
                        BlackoutTimerSystem.isPermanentBlackoutActive(currentLevel),
                        phase.ordinal());
            }

            BlackoutSheriffVoteManager.tickSecond(currentLevel).ifPresent(this::applySheriffVoteResult);
            checkSanityDeaths();
            checkVictory();

            // checkVictory/endGame 可能在本 tick 内同步触发 onEnd，把 currentLevel 置 null，
            // 此后不得再访问 currentLevel，否则会 NPE 崩服。
            if (currentLevel == null || gameEnded) return;

            if (tickAccumulator % 40 == 0 && BlackoutTimerSystem.isPermanentBlackoutActive(currentLevel)) {
                reapplyPermanentBlackout();
            }
        }
    }

    @Override
    public void onTaskComplete(ServerPlayer player, TaskInstance task) {
        // 杀手双任务附属奖励钩子（暂时留空）：
        // 杀手完成真任务(BLACKOUT_BAD)或假任务(BLACKOUT_GOOD)时在此分发对应的附属奖励。
        // 两个任务完成都给金币奖励（金币由 RoleMethodDispatcherMixin 在 callOnFinishQuest 路径发放），
        // 附属奖励根据杀手选择做的任务触发，此处为预留扩展点。
        if (currentLevel != null && player != null && task != null) {
            TaskCategory cat = task.getDefinition().getCategory();
            if (BLACKOUT_BAD.equals(cat)) {
                onKillerRealTaskComplete(player, task);
            } else if (BLACKOUT_GOOD.equals(cat)) {
                onKillerFakeTaskComplete(player, task);
            }
        }
        checkVictory();
    }

    @Override
    public void onTaskAssign(ServerPlayer player, TaskInstance task) {
        if (task != null && ONCE_PER_GAME_TASK_IDS.contains(task.getFullId())) {
            assignedOncePerGameTasks.add(task.getFullId());
            HabiTrainCore.LOGGER.info("[Blackout] Once-per-game task {} assigned to {}, will not reassign this round",
                    task.getFullId(), player.getName().getString());
        }
    }

    /**
     * 杀手完成"真任务"(BLACKOUT_BAD)时的附属奖励钩子（暂时留空）。
     * 金币奖励已由 RoleMethodDispatcherMixin 发放，此处用于后续扩展专属附属奖励。
     */
    protected void onKillerRealTaskComplete(ServerPlayer player, TaskInstance task) {
        // TODO: 杀手真任务附属奖励
    }

    /**
     * 杀手完成"假任务"(BLACKOUT_GOOD)时的附属奖励钩子（暂时留空）。
     * 金币奖励已由 RoleMethodDispatcherMixin 发放，此处用于后续扩展专属附属奖励。
     */
    protected void onKillerFakeTaskComplete(ServerPlayer player, TaskInstance task) {
        // TODO: 杀手假任务附属奖励
    }

    @Override
    public void onPlayerJoin(ServerPlayer player) {
        BlackoutSheriffVoteManager.onPlayerJoined(currentLevel, player);
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        BlackoutRoleManager.eliminate(currentLevel, player.getUUID());
        checkVictory();
    }

    @Override
    public void onEnd(ServerLevel level, WinResult result) {
        String message = pendingEndMessage != null ? pendingEndMessage : "结束对局";
        broadcast(message);
        pendingEndMessage = null;
        if (level != null && level.getServer() != null) {
            com.habitrain.core.network.BlackoutSheriffVotePayload.broadcastToAll(level.getServer(), false, 0, 15, 1, java.util.List.of());
        }
        syncClientBlackoutReset(level);
        currentLevel = null;
    }

    @Override
    public void onCleanup(ServerLevel level) {
        if (level != null && level.getServer() != null) {
            com.habitrain.core.network.BlackoutSheriffVotePayload.broadcastToAll(level.getServer(), false, 0, 15, 1, java.util.List.of());
        }
        syncClientBlackoutReset(level);
        // 注意：不在此处清除 BlackoutRoleManager 的角色状态——SRE 的 showReplay 结束通报
        // 在本 tick 之后才执行，需要读取 roleHistory 来展示全员身份。状态会在下一局
        // initRandomAssignment 开始时 clear，不会泄漏到下一局。
        BlackoutSheriffVoteManager.reset(level);
        BlackoutTimerSystem.reset(level);
        BlackoutShopService.resetRound(level);
        currentLevel = null;
        gameEnded = false;
        sreGameRunning = false;
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

    private void triggerSREPermanentBlackout() {
        if (currentLevel == null) return;
        var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(currentLevel);
        if (blackout != null) {
            blackout.triggerBlackout(true, 600000);
        }

        com.habitrain.core.game.blackout.task.RestorePowerHandler.resetCompleted();
        forceAssignRestorePowerToAllGood();
    }

    private void forceAssignRestorePowerToAllGood() {
        if (currentLevel == null) return;
        TaskManager mgr = TaskManager.getInstance();
        TaskDefinition restoreDef = TaskRegistry.get("habitrain_core:restore_power");
        if (restoreDef == null) return;

        for (UUID uuid : BlackoutRoleManager.getAllAlive(currentLevel)) {
            if (BlackoutRoleManager.getFaction(currentLevel, uuid) != BlackoutRoleManager.Faction.GOOD) continue;

            // 在派发 restore_power 之前，对正在做供电池任务的玩家先同步完成给奖励，
            // 避免直接清空导致玩家在 add_coal/repair_wiring 中途任务消失无奖励。
            // 注意：这里调用 syncCompletion 会让所有正在做同任务的好人玩家一起完成，
            // 但此处是全员循环，每个玩家都触发一次 syncCompletion 是重复的——所以这里只
            // 在 removeActiveTask 前先 fire 一次 onComplete 给该玩家自己（不广播同步）。
            TaskInstance existing = mgr.getActiveTask(uuid);
            if (existing != null && !existing.isFulfilled()
                    && existing.getFullId() != null
                    && existing.getFullId().startsWith("habitrain_core:")) {
                ServerPlayer existingPlayer = currentLevel.getServer().getPlayerList().getPlayer(uuid);
                if (existingPlayer != null) {
                    try {
                        existing.setFulfilled(true);
                        existing.getDefinition().onComplete(existingPlayer, existing);
                    } catch (Throwable t) {
                        HabiTrainCore.LOGGER.error(
                                "forceAssignRestorePowerToAllGood: failed to complete existing task {} for {}",
                                existing.getFullId(), uuid, t);
                    }
                }
            }

            mgr.removeActiveTask(uuid);
            mgr.clearBlackoutRotationFlag(uuid);

            ServerPlayer sp = currentLevel.getServer().getPlayerList().getPlayer(uuid);

            if (sp != null) {
                try {
                    io.wifi.starrailexpress.cca.SREPlayerTaskComponent taskComp =
                            io.wifi.starrailexpress.cca.SREPlayerTaskComponent.KEY.get(sp);
                    if (taskComp != null) {
                        taskComp.clear();
                    }
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.error("forceAssignRestorePowerToAllGood: failed to clear SRE tasks for {}", uuid, t);
                }
            }

            TaskInstance instance = new TaskInstance(restoreDef);
            if (sp != null) {
                restoreDef.onAssign(sp, instance);
            }
            mgr.setActiveTask(uuid, instance);

            if (sp != null) {
                ActiveTaskPayload.sendToPlayer(sp, restoreDef.getFullId());
            }
        }
    }

    private void endSREBlackout() {
        if (currentLevel == null) return;
        var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(currentLevel);
        if (blackout != null) {
            blackout.reset();
        }
        broadcast("\u00a7a\u4f9b\u7535\u5df2\u6062\u590d");
    }

    private void reapplyPermanentBlackout() {
        if (currentLevel == null) return;
        try {
            var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(currentLevel);
            // \u4ec5\u5728\u505c\u7535\u5df2\u5931\u6548\uff08\u88ab\u5916\u90e8\u6e05\u9664/\u5230\u671f\uff09\u65f6\u624d\u91cd\u65b0\u89e6\u53d1\uff0c\u907f\u514d\u6bcf 2 \u79d2\u91cd\u590d\u64ad\u653e\u5173\u706f\u97f3\u6548\u3002
            if (blackout != null && !blackout.isBlackoutActive()) {
                blackout.triggerBlackout(false, 600000);
                HabiTrainCore.LOGGER.debug("Re-applied permanent blackout via API (recovery)");
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("Failed to reapply blackout", e);
        }
    }

    private void sendTimeWarning() {
        // Blackout mode intentionally hides all timer UI and warning text.
    }

    /**
     * 理智条清空判死：每秒检查所有存活的好人阵营玩家，当 SRE 的
     * {@link io.wifi.starrailexpress.cca.SREPlayerMoodComponent} 理智值降至
     * {@code isLowerThanDepressed} 阈值时，立即判定其对局死亡。
     * <p>
     * 仅对好人阵营生效（杀手/中立不受理智清空影响）。死亡通过 SRE 的
     * {@link io.wifi.starrailexpress.game.GameUtils#killPlayer} 执行标准死亡流程
     * （变旁观、记回放），并从 {@link BlackoutRoleManager} 阵营状态中淘汰。
     */
    private void checkSanityDeaths() {
        if (currentLevel == null || gameEnded) return;
        var server = currentLevel.getServer();
        if (server == null) return;

        for (ServerPlayer player : currentLevel.players()) {
            if (player.isSpectator() || player.isCreative()) continue;
            UUID id = player.getUUID();
            if (!BlackoutRoleManager.isAlive(currentLevel, id)) continue;
            if (BlackoutRoleManager.getFaction(currentLevel, id) != BlackoutRoleManager.Faction.GOOD) continue;

            try {
                var mood = io.wifi.starrailexpress.cca.SREPlayerMoodComponent.KEY.get(player);
                if (mood == null) continue;
                if (!mood.isLowerThanDepressed()) continue;

                // 理智崩溃 → 判定对局死亡（不广播提示，死因通过回放翻译键显示）
                GameUtils.killPlayer(player, true, null,
                        ResourceLocation.fromNamespaceAndPath("habitrain_core", "sanity_collapse"));
                BlackoutRoleManager.eliminate(currentLevel, id);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.error("checkSanityDeaths: failed for player {}", id, t);
            }
        }
    }

    private void checkVictory() {
        if (currentLevel == null) return;
        int goodRemaining = BlackoutRoleManager.getRemainingGood(currentLevel);
        int badRemaining = BlackoutRoleManager.getRemainingBad(currentLevel);

        if (goodRemaining <= 0 && badRemaining <= 0) {
            lastWinningFaction = null;
            endGame(WinResult.noWinner("同归于尽"), "双方同归于尽，游戏结束。");
            return;
        }

        if (BlackoutTimerSystem.isTimeUp(currentLevel)) {
            lastWinningFaction = BlackoutRoleManager.Faction.GOOD;
            endGame(WinResult.noWinner("\u65f6\u95f4\u5f52\u96f6"), "\u00a7a\u597d\u4eba\u9635\u8425\u83b7\u80dc\uff01\u65f6\u95f4\u5f52\u96f6\uff0c\u597d\u4eba\u6210\u529f\u5b58\u6d3b\uff01");
            return;
        }

        if (badRemaining <= 0 && goodRemaining > 0) {
            lastWinningFaction = BlackoutRoleManager.Faction.GOOD;
            endGame(WinResult.noWinner("\u6740\u624b\u5168\u706d"), "\u00a7a\u597d\u4eba\u9635\u8425\u83b7\u80dc\uff01\u6240\u6709\u6740\u624b\u5df2\u88ab\u6d88\u706d");
            return;
        }

        if (goodRemaining <= 0 && badRemaining > 0) {
            lastWinningFaction = BlackoutRoleManager.Faction.BAD;
            endGame(WinResult.noWinner("\u597d\u4eba\u5168\u706d"), "\u00a7c\u6740\u624b\u9635\u8425\u83b7\u80dc\uff01\u6240\u6709\u597d\u4eba\u90fd\u88ab\u6dd8\u6c70\u4e86");
        }
    }

    private void endGame(WinResult result, String message) {
        if (gameEnded) return;
        gameEnded = true;
        pendingEndMessage = message;
        if (currentLevel != null) {
            try {
                var sreGame = SREGameWorldComponent.KEY.get(currentLevel);
                if (sreGame != null) {
                    sreGame.setGameStatus(io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.STOPPING);
                    sreGame.clearRoleMap();
                }
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("endGame: failed to stop SRE game", e);
            }
            GameModeRegistry.stop(currentLevel, result);
        }
    }

    private void endGame(String message) {
        lastWinningFaction = null;
        endGame(WinResult.forceEnd("\u6e38\u620f\u7ed3\u675f"), message);
    }

    private void applySheriffVoteResult(VoteResolution resolution) {
        if (currentLevel == null || resolution == null) return;
        if (resolution.winnerIds().isEmpty()) return;

        try {
            java.util.Random random = new java.util.Random(currentLevel.getRandom().nextLong());
            Map<UUID, ServerPlayer> playerMap = new HashMap<>();
            for (ServerPlayer player : currentLevel.players()) {
                playerMap.put(player.getUUID(), player);
            }

            var gameWorld = SREGameWorldComponent.KEY.get(currentLevel);

            for (int i = 0; i < resolution.winnerIds().size(); i++) {
                UUID winnerId = resolution.winnerIds().get(i);
                boolean wasKiller = resolution.winnerWasKillers().get(i);
                ServerPlayer player = playerMap.get(winnerId);
                if (player == null) continue;

                BlackoutRoleManager.Faction currentFaction =
                        BlackoutRoleManager.getFaction(currentLevel, player.getUUID());

                if (wasKiller || currentFaction == BlackoutRoleManager.Faction.BAD) {
                    // === 杀手被票选为警长：身份不变，直接给予一次性手枪 ===
                    // 不切换职业（不调 gameWorld.addRole），不发 200 金币奖励，
                    // 不走 setSheriff(level, playerId, policeRole, factionOverride) 路径。
                    // 仅加入 sheriffs 集合以保留警长特权（/habi_api buy_gun 等）。
                    // 投票广播保持不变（"当选警长：xxx"），玩家在投票中正常显示为警长。
                    BlackoutRoleManager.setSheriff(currentLevel, player.getUUID());

                    // 给予一把左轮手枪（trainmurdermystery:revolver）
                    var revolverItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .get(net.minecraft.resources.ResourceLocation.parse("trainmurdermystery:revolver"));
                    if (revolverItem != null && revolverItem != net.minecraft.world.item.Items.AIR) {
                        net.minecraft.world.item.ItemStack gun = new net.minecraft.world.item.ItemStack(revolverItem, 1);
                        boolean added = player.getInventory().add(gun);
                        if (!added) {
                            player.drop(gun, false);
                        }
                        SubtitleNotifier.sendTop(player,
                                Component.literal("\u00a76\u8b66\u957f\u5165\u573a"),
                                Component.literal("\u00a76\u4f60\u88ab\u7968\u9009\u4e3a\u8b66\u957f\uff0c\u83b7\u5f97\u4e86\u4e00\u628a\u5de6\u8f6e\u624b\u67aa\u3002"),
                                80);
                    }
                    HabiTrainCore.LOGGER.info("[SheriffVote] killer {} voted as sheriff, kept killer identity + given revolver",
                            player.getName().getString());
                } else {
                    // === 好人被票选为警长：保留原有逻辑 ===
                    // 随机一个 SRE 原版警察职业作为被票选者的可见身份。
                    io.wifi.starrailexpress.api.SRERole policeRole = BlackoutRoleManager.getRandomPoliceRole(random);
                    if (policeRole == null) continue;
                    BlackoutRoleManager.setSheriff(currentLevel, player.getUUID(), policeRole, null);

                    String roleName = policeRole.getName().getString();
                    String subtitle = policeRole.getDescription().getString();
                    String goal = policeRole.getGoal().getString();
                    ServerPlayNetworking.send(player, new BlackoutAnnouncePayload(
                            roleName,
                            subtitle,
                            goal,
                            BlackoutRoleManager.getRemainingBad(currentLevel),
                            BlackoutRoleManager.getRemainingGood(currentLevel)
                    ));

                    var shop = SREPlayerShopComponent.KEY.get(player);
                    if (shop != null) {
                        shop.addToBalance(200);
                    }
                    SubtitleNotifier.sendTop(player,
                            Component.literal("\u00a76\u8b66\u957f\u5165\u573a"),
                            Component.literal("\u00a76\u4f60\u56e0\u4e3a\u88ab\u7968\u9009\u4e3a\u8b66\u957f\u83b7\u5f97\u4e86 200 \u91d1\u5e01\u3002"),
                            80);
                }
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("Failed to grant sheriff vote reward", e);
        }
    }

    private void syncClientBlackoutReset(ServerLevel level) {
        if (level == null || level.getServer() == null) return;
        BlackoutTimerPayload.broadcastToAll(level.getServer(), 0, 0, false, 0);
    }

    public static void broadcast(ServerLevel level, String message) {
        if (level == null) return;
        Component component = Component.literal(message);
        for (ServerPlayer player : level.players()) {
            // 聊天栏在关灯模式被屏蔽，改用屏幕顶部派发 GUI（SubtitleNotifier）
            SubtitleNotifier.sendTop(player, Component.empty(), component, 80);
        }
    }

    private void broadcast(String message) {
        broadcast(currentLevel, message);
    }

    public static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }

    public static BlackoutRoleManager.Faction getLastWinningFaction() {
        return lastWinningFaction;
    }
}
