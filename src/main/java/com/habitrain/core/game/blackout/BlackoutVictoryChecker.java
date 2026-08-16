package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.api.WinResult;
import com.habitrain.core.game.blackout.shop.BlackoutTaskShopService;
import com.habitrain.core.game.blackout.shop.BlackoutTaskShopState;
import com.habitrain.core.game.sre.role.sins.win.SinVictoryHooks;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.role.behavior.RoleEventDispatcher;
import com.habitrain.core.role.behavior.WinFoldResult;
import com.habitrain.core.task.TaskManager;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREWorldBlackoutComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

public class BlackoutVictoryChecker {
    private final BlackoutMode mode;
    private final BlackoutSyncManager syncManager;

    BlackoutVictoryChecker(BlackoutMode mode, BlackoutSyncManager syncManager) {
        this.mode = mode;
        this.syncManager = syncManager;
    }

    void tickSecond(ServerLevel level) {
        checkSanityDeaths(level);
        checkVictory(level);
    }

    private void checkSanityDeaths(ServerLevel level) {
        if (!mode.hasRound(level) || mode.isGameEnded(level)) return;
        var server = level.getServer();
        if (server == null) return;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.isCreative()) continue;
            UUID id = player.getUUID();
            if (!BlackoutRoleManager.isAlive(level, id)) continue;
            if (BlackoutRoleManager.getFaction(level, id) != BlackoutRoleManager.Faction.GOOD) continue;

            try {
                var mood = io.wifi.starrailexpress.cca.SREPlayerMoodComponent.KEY.get(player);
                if (mood == null) continue;
                if (!mood.isLowerThanDepressed()) continue;

                GameUtils.killPlayer(player, true, null,
                        ResourceLocation.fromNamespaceAndPath("habitrain_core", "sanity_collapse"));
                BlackoutRoleManager.eliminate(level, id);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.error("checkSanityDeaths: failed for player {}", id, t);
            }
        }
    }

    void checkVictory(ServerLevel level) {
        if (!mode.hasRound(level)) return;
        if (mode.isGameEnded(level)) return;

        // 1) Pride last survivor among assigned alive → custom pride win.
        if (SinVictoryHooks.isOnlyPrideAlive(level)) {
            endGamePrideCustom(level);
            return;
        }

        // 2) Greed collection complete → custom greed win.
        if (SinVictoryHooks.isGreedWinReady(level)) {
            endGameGreedCustomInstance(level, SinVictoryHooks.findAliveGreedPlayer(level));
            return;
        }

        // 3) MODIFY win-condition hooks from role override API.
        WinResult hookResult = RoleOverrideWinHook.check(level);
        if (hookResult != null) {
            mode.setLastWinningFaction(level, null);
            endGame(level, hookResult, hookResult.getReason());
            return;
        }

        // 3b) v2 win hooks. One unified fold for gate + winner patch (shared with
        // the standard SRE murder chain). evaluateWin can still declare a custom
        // winner; allowGameEnd DENY is pride-style and may only block "killers
        // wiped → GOOD win". BAD wipe / timer must still resolve, otherwise pride
        // coexisting with killers freezes the match.
        WinFoldResult v2Fold = RoleEventDispatcher.INSTANCE.foldWin(level, "BLACKOUT", false);
        WinResult v2Win = v2Fold.toWinResult();
        if (v2Win != null) {
            mode.setLastWinningFaction(level, null);
            endGame(level, v2Win, v2Win.getReason());
            return;
        }
        boolean v2BlocksGoodWin = v2Fold.denied();

        int goodRemaining = BlackoutRoleManager.getRemainingGood(level);
        int badRemaining = BlackoutRoleManager.getRemainingBad(level);
        // SIN_* never enter getRemainingGood/Bad; pride block can still
        // prevent wipe ends while independent sins remain alive.
        boolean prideBlocking = SinVictoryHooks.isPrideBlocking(level);

        if (goodRemaining <= 0 && badRemaining <= 0) {
            // Only GOOD/BAD wiped — SIN_* may still be alive.
            if (prideBlocking) {
                return;
            }
            // Sloth still alive among independents → sloth custom win.
            if (SinVictoryHooks.isSlothAlive(level)) {
                endGameSlothCustom(level);
                return;
            }
            mode.setLastWinningFaction(level, null);
            endGame(level, WinResult.noWinner("同归于尽"), "双方同归于尽，游戏结束。");
            return;
        }
        // 阵营全灭优先于计时器。尤其 GOOD 已全灭且傲慢仍与 BAD 共存时，
        // prideBlocking 只能阻止“杀手全灭”的 GOOD 结算，不能把已经成立的 BAD 胜拖到
        // timer 分支反判为 GOOD 胜。
        if (goodRemaining <= 0 && badRemaining > 0) {
            if (SinVictoryHooks.isSlothAlive(level)) {
                endGameSlothCustom(level);
                return;
            }
            mode.setLastWinningFaction(level, BlackoutRoleManager.Faction.BAD);
            endGame(level, WinResult.noWinner("好人全灭"), "§c杀手阵营获胜！所有好人都被淘汰了");
            return;
        }
        if (badRemaining <= 0 && goodRemaining > 0) {
            if (prideBlocking || v2BlocksGoodWin) {
                return;
            }
            if (SinVictoryHooks.isSlothAlive(level)) {
                endGameSlothCustom(level);
                return;
            }
            mode.setLastWinningFaction(level, BlackoutRoleManager.Faction.GOOD);
            endGame(level, WinResult.noWinner("杀手全灭"), "§a好人阵营获胜！所有杀手已被消灭");
            return;
        }
        // Timer can still end if pride is alive, but only after wipe outcomes above.
        if (BlackoutTimerSystem.isTimeUp(level)) {
            if (SinVictoryHooks.isSlothAlive(level)) {
                endGameSlothCustom(level);
                return;
            }
            mode.setLastWinningFaction(level, BlackoutRoleManager.Faction.GOOD);
            endGame(level, WinResult.noWinner("时间归零"), "§a好人阵营获胜！时间归零，好人成功存活！");
        }
    }

    private void endGamePrideCustom(ServerLevel level) {
        if (mode.isGameEnded(level)) return;
        mode.setLastWinningFaction(level, null);
        mode.setGameEnded(level, true);
        String message = "§6傲慢·路西法获胜！成为最后的幸存者。";
        mode.setPendingEndMessage(level, message);
        mode.setPendingWinResult(level, WinResult.noWinner("傲慢独立胜"));
        if (level == null) return;
        try {
            populateRoundEndDataCustomSin(level, com.habitrain.core.game.sre.role.sins.SevenSins.PRIDE_ID);
            mode.setPendingEndMessage(level, null);
            HabiTrainCore.LOGGER.info("[Blackout] game end: {}", message);
            var sreGame = SREGameWorldComponent.KEY.get(level);
            if (sreGame != null) {
                sreGame.setGameStatus(SREGameWorldComponent.GameStatus.STOPPING);
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("endGamePrideCustom failed", e);
            com.habitrain.core.api.GameModeRegistry.stop(level, WinResult.noWinner("傲慢独立胜"));
        }
    }

    private void endGameSlothCustom(ServerLevel level) {
        if (mode.isGameEnded(level)) return;
        mode.setLastWinningFaction(level, null);
        mode.setGameEnded(level, true);
        String message = "§9懒惰·贝露菲格露获胜！在阵营胜负中窃取了胜利。";
        mode.setPendingEndMessage(level, message);
        mode.setPendingWinResult(level, WinResult.noWinner("懒惰独立胜"));
        if (level == null) return;
        try {
            populateRoundEndDataCustomSin(level, com.habitrain.core.game.sre.role.sins.SevenSins.SLOTH_ID);
            mode.setPendingEndMessage(level, null);
            HabiTrainCore.LOGGER.info("[Blackout] game end: {}", message);
            var sreGame = SREGameWorldComponent.KEY.get(level);
            if (sreGame != null) {
                sreGame.setGameStatus(SREGameWorldComponent.GameStatus.STOPPING);
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("endGameSlothCustom failed", e);
            com.habitrain.core.api.GameModeRegistry.stop(level, WinResult.noWinner("懒惰独立胜"));
        }
    }

    private void endGameGreedCustomInstance(ServerLevel level, ServerPlayer winner) {
        if (mode.isGameEnded(level)) return;
        mode.setLastWinningFaction(level, null);
        mode.setGameEnded(level, true);
        String message = "§6贪婪·玛门获胜！收纳袋收集完成。";
        mode.setPendingEndMessage(level, message);
        mode.setPendingWinResult(level, WinResult.noWinner("贪婪独立胜"));
        if (level == null) return;
        try {
            populateRoundEndDataCustomSin(level, com.habitrain.core.game.sre.role.sins.SevenSins.GREED_ID);
            mode.setPendingEndMessage(level, null);
            HabiTrainCore.LOGGER.info("[Blackout] game end: {} winner={}",
                    message, winner != null ? winner.getUUID() : null);
            var sreGame = SREGameWorldComponent.KEY.get(level);
            if (sreGame != null) {
                sreGame.setGameStatus(SREGameWorldComponent.GameStatus.STOPPING);
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("endGameGreedCustom failed", e);
            com.habitrain.core.api.GameModeRegistry.stop(level, WinResult.noWinner("贪婪独立胜"));
        }
    }

    /**
     * Instant greed win from collection (callable outside tick).
     * No-op if blackout mode is not active for this level.
     */
    public static void endGameGreedCustom(ServerLevel level, ServerPlayer winner) {
        if (level == null) return;
        try {
            var activeOpt = com.habitrain.core.api.GameModeRegistry.getActiveForLevel(level);
            if (activeOpt.isEmpty() || !(activeOpt.get() instanceof BlackoutMode blackout)) return;
            if (blackout.isGameEnded(level)) return;
            var vc = blackout.getVictoryChecker(level);
            if (vc == null) return;
            vc.endGameGreedCustomInstance(level, winner);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.debug("[Blackout] endGameGreedCustom skipped: {}", t.toString());
        }
    }

    /**
     * Write CUSTOM round-end for an independent sin (pride last survivor / sloth hijack).
     * Personal wins: only players whose role history matches the sin id.
     */
    public static void populateRoundEndDataCustomSin(ServerLevel level, ResourceLocation sinRoleId) {
        if (level == null || sinRoleId == null) return;
        try {
            SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
            if (roundEnd == null) return;

            Map<UUID, ResourceLocation> history = BlackoutRoleManager.getRoleHistory(level);
            java.util.List<ServerPlayer> participants = new java.util.ArrayList<>();
            for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                if (history.containsKey(p.getUUID())) {
                    participants.add(p);
                }
            }
            if (participants.isEmpty()) {
                participants.addAll(level.players());
            }

            roundEnd.setRoundEndData(participants, GameUtils.WinStatus.CUSTOM);
            addOfflineRoundEndParticipants(level, roundEnd, history);
            try {
                // Public fields on SREGameRoundEndComponent (SRE 4.3).
                roundEnd.CustomWinnerID = sinRoleId.getPath();
                // Pride red / Sloth slate / Lust pink default by id.
                if (com.habitrain.core.game.sre.role.sins.SevenSins.SLOTH_ID.equals(sinRoleId)) {
                    roundEnd.CustomWinnerColor = 0x64648C;
                } else if (com.habitrain.core.game.sre.role.sins.SevenSins.LUST_ID.equals(sinRoleId)) {
                    roundEnd.CustomWinnerColor = 0xC83296;
                } else if (com.habitrain.core.game.sre.role.sins.SevenSins.GREED_ID.equals(sinRoleId)) {
                    roundEnd.CustomWinnerColor = 0xC8A014;
                } else {
                    roundEnd.CustomWinnerColor = 0xB42828;
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Blackout] could not set CustomWinner fields", t);
            }
            for (ServerPlayer p : participants) {
                ResourceLocation roleId = history.get(p.getUUID());
                boolean didWin = sinRoleId.equals(roleId);
                roundEnd.setPlayerWin(p.getUUID(), didWin);
            }
            for (UUID offlineId : BlackoutRoleManager.getAllAlive(level)) {
                if (level.getServer() == null || level.getServer().getPlayerList().getPlayer(offlineId) != null) continue;
                ResourceLocation roleId = history.get(offlineId);
                roundEnd.setPlayerWin(offlineId, sinRoleId.equals(roleId));
            }
            roundEnd.sync();
            HabiTrainCore.LOGGER.info("[Blackout] custom sin round-end: path={}, participants={}",
                    sinRoleId.getPath(), participants.size());
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("populateRoundEndDataCustomSin failed", t);
        }
    }

    /**
     * Adds offline-but-still-alive (disconnect grace) players to the SRE round-end
     * component so they are not dropped from personal win/round-end data.
     */
    private static void addOfflineRoundEndParticipants(
            ServerLevel level,
            SREGameRoundEndComponent roundEnd,
            Map<UUID, ResourceLocation> history) {
        if (level == null || roundEnd == null || history == null) return;
        for (UUID id : BlackoutRoleManager.getAllAlive(level)) {
            if (level.getServer() == null || level.getServer().getPlayerList().getPlayer(id) != null) continue;
            if (!history.containsKey(id)) continue;
            boolean already = false;
            for (var data : roundEnd.players) {
                if (data.player.getId().equals(id)) {
                    already = true;
                    break;
                }
            }
            if (!already) {
                roundEnd.players.add(roundEnd.new RoundEndData(
                        new com.mojang.authlib.GameProfile(id, ""), false, false));
            }
        }
    }

    void endGame(ServerLevel level, WinResult result, String message) {
        if (mode.isGameEnded(level)) return;
        mode.setGameEnded(level, true);
        mode.setPendingEndMessage(level, message);
        mode.setPendingWinResult(level, result);
        if (level != null) {
            try {
                // 对齐 SRE 原版：先写 roundEnd，再进入 STOPPING。
                // 不要 clearRoleMap：客户端 lastRole 依赖 AnnounceEnding 时角色表仍在。
                // 不要立刻 GameModeRegistry.stop：finalize 仍需 lastWinningFaction。
                populateRoundEndData(level, mode.getLastWinningFaction(level));

                // 不发 habitrain 胜利 TOP 补充弹窗；SRE 结算 UI 由 populateRoundEndData + STOPPING 驱动。
                if (message != null && !message.isEmpty()) {
                    mode.setPendingEndMessage(level, null);
                    HabiTrainCore.LOGGER.info("[Blackout] game end: {}", message);
                }

                var sreGame = SREGameWorldComponent.KEY.get(level);

                if (sreGame != null) {
                    sreGame.setGameStatus(SREGameWorldComponent.GameStatus.STOPPING);
                }
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("endGame: failed to stop SRE game", e);
                // 回退：至少把 habitrain 模式停掉，避免卡死
                com.habitrain.core.api.GameModeRegistry.stop(level, result);
            }
        }
    }

    /**
     * 写入 SRE 结算组件（胜负阵营 + 参与者 + 个人胜负）。
     * 在 STOPPING 前调用，确保客户端在 fade/AnnounceEnding 期间已有非 NONE 的 winStatus。
     */
    public static void populateRoundEndData(ServerLevel level, BlackoutRoleManager.Faction winner) {
        if (level == null) return;
        try {
            SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
            if (roundEnd == null) return;

            GameUtils.WinStatus winStatus;
            if (winner == BlackoutRoleManager.Faction.GOOD) {
                winStatus = GameUtils.WinStatus.PASSENGERS;
            } else if (winner == BlackoutRoleManager.Faction.BAD) {
                winStatus = GameUtils.WinStatus.KILLERS;
            } else {
                // 同归于尽 / 外部强制结束：用 NO_PLAYER 避免 RoundTextRenderer 在 NONE 时整段不画
                winStatus = GameUtils.WinStatus.NO_PLAYER;
            }

            Map<UUID, ResourceLocation> history = BlackoutRoleManager.getRoleHistory(level);
            java.util.List<ServerPlayer> participants = new java.util.ArrayList<>();
            for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                if (history.containsKey(p.getUUID())) {
                    participants.add(p);
                }
            }
            // 若历史为空（异常），至少用当前在线玩家，避免 players 列表全空
            if (participants.isEmpty()) {
                participants.addAll(level.players());
            }

            roundEnd.setRoundEndData(participants, winStatus);
            addOfflineRoundEndParticipants(level, roundEnd, history);

            // Personal wins: matching faction wins; SIN_KILLER_SHARE (wrath) also
            // wins when killers (BAD) win. SIN_INDEPENDENT only wins on custom
            // sin ends (later tasks).
            // Custom sin win approach (probed SREGameRoundEndComponent): prefer
            // WinStatus.CUSTOM + CustomWinnerID/Color/players when a later task
            // ends for pride/greed/lust/sloth; fallback NO_PLAYER + message if
            // setRoundEndData path cannot carry CUSTOM cleanly.
            for (ServerPlayer p : participants) {
                BlackoutRoleManager.Faction f = BlackoutRoleManager.getFactionForEnd(level, p.getUUID());
                boolean didWin = false;
                if (winner != null) {
                    if (f == winner) {
                        didWin = true;
                    } else if (winner == BlackoutRoleManager.Faction.BAD
                            && f == BlackoutRoleManager.Faction.SIN_KILLER_SHARE) {
                        didWin = true;
                    }
                }
                roundEnd.setPlayerWin(p.getUUID(), didWin);
            }
            for (UUID offlineId : BlackoutRoleManager.getAllAlive(level)) {
                if (level.getServer() == null || level.getServer().getPlayerList().getPlayer(offlineId) != null) continue;
                BlackoutRoleManager.Faction f = BlackoutRoleManager.getFactionForEnd(level, offlineId);
                boolean didWin = false;
                if (winner != null) {
                    if (f == winner) {
                        didWin = true;
                    } else if (winner == BlackoutRoleManager.Faction.BAD
                            && f == BlackoutRoleManager.Faction.SIN_KILLER_SHARE) {
                        didWin = true;
                    }
                }
                roundEnd.setPlayerWin(offlineId, didWin);
            }
            roundEnd.sync();
            HabiTrainCore.LOGGER.info("[Blackout] round-end populated: winStatus={}, winner={}, participants={}",
                    winStatus, winner, participants.size());
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("populateRoundEndData failed", t);
        }
    }

    void endGame(ServerLevel level, String message) {
        mode.setLastWinningFaction(level, null);
        endGame(level, WinResult.forceEnd("游戏结束"), message);
    }

    void triggerSREPermanentBlackout(ServerLevel level) {
        if (level == null) return;
        // 计时器到点的永久停电：若本局已恢复过供电，则第二次停电不再派发恢复供电（无法恢复）。
        if (BlackoutTaskShopState.isRestoreUsed(level)) {
            HabiTrainCore.LOGGER.info("[Blackout] Permanent blackout but restoreUsed=true, no restore dispatch");
            try {
                var blackout = SREWorldBlackoutComponent.KEY.get(level);
                if (blackout != null) blackout.triggerBlackout(true, 600000);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.error("triggerSREPermanentBlackout: SRE blackout failed", t);
            }
            return;
        }
        var blackout = SREWorldBlackoutComponent.KEY.get(level);
        if (blackout != null) {
            blackout.triggerBlackout(true, 600000);
        }
        com.habitrain.core.game.blackout.task.RestorePowerHandler.resetCompleted(level);
        forceAssignRestorePowerToAllGood(level);
    }

    /**
     * 强制给所有存活好人派发恢复供电。
     * 派发前以「无奖励取消」方式替换当前任务（停电专属任务 onRemove + 回收；原版 SRE clear），
     * 不再调 onComplete 发奖，对齐任务商店购买语义。
     * 包可见，供炸毁发电机路径通过 {@link BlackoutVictoryCheckerAccessor} 调用。
     */
    void forceAssignRestorePowerToAllGood(ServerLevel level) {
        if (level == null) return;
        TaskManager mgr = TaskManager.getInstance();
        var restoreDef = TaskRegistry.get(com.habitrain.core.game.blackout.BlackoutExclusiveTasks.TASK_RESTORE_POWER);
        if (restoreDef == null) return;

        int assigned = 0;
        for (UUID uuid : BlackoutRoleManager.getAllAlive(level)) {
            if (BlackoutRoleManager.getFaction(level, uuid) != BlackoutRoleManager.Faction.GOOD) continue;

            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(uuid);
            // 断线宽限玩家重连后会由正常同步/派发路径处理；不能创建未执行 onAssign 的任务实例。
            if (sp == null || !BlackoutRoleManager.isInteractable(level, uuid)) continue;

            // 无奖励取消当前任务（停电专属 onRemove + 回收；原版 SRE clear）
            BlackoutTaskShopService.cancelWithoutReward(level, sp);

            TaskInstance instance = new TaskInstance(restoreDef);
            instance.setDimension(level.dimension());
            restoreDef.onAssign(sp, instance);
            mgr.setActiveTask(uuid, instance);

            ActiveTaskPayload.sendToPlayer(sp, restoreDef.getFullId());
            ExclusiveTaskHudSync.insert(sp, instance);
            assigned++;
        }
        if (assigned > 0) {
            HabiTrainCore.LOGGER.info("[Blackout] force-assigned restore_power to {} good players", assigned);
        }
    }

    void reapplyPermanentBlackout(ServerLevel level) {
        if (level == null) return;
        try {
            var blackout = SREWorldBlackoutComponent.KEY.get(level);
            if (blackout != null && !blackout.isBlackoutActive()) {
                blackout.triggerBlackout(false, 600000);
                HabiTrainCore.LOGGER.debug("Re-applied permanent blackout via API (recovery)");
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("Failed to reapply blackout", e);
        }
    }

    void endSREBlackout(ServerLevel level) {
        if (level == null) return;
        var blackout = SREWorldBlackoutComponent.KEY.get(level);
        if (blackout != null) {
            blackout.reset();
        }
        // 恢复提示由 BlackoutTimerSystem.restorePower 统一广播，此处不重复发。
    }
}
