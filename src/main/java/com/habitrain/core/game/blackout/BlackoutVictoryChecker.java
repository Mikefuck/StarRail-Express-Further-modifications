package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.api.WinResult;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.TaskManager;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREWorldBlackoutComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

class BlackoutVictoryChecker {
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
        if (mode.getCurrentLevel() == null || mode.isGameEnded()) return;
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
        if (mode.getCurrentLevel() == null) return;
        int goodRemaining = BlackoutRoleManager.getRemainingGood(level);
        int badRemaining = BlackoutRoleManager.getRemainingBad(level);

        if (goodRemaining <= 0 && badRemaining <= 0) {
            mode.setLastWinningFaction(null);
            endGame(level, WinResult.noWinner("同归于尽"), "双方同归于尽，游戏结束。");
            return;
        }
        if (BlackoutTimerSystem.isTimeUp(level)) {
            mode.setLastWinningFaction(BlackoutRoleManager.Faction.GOOD);
            endGame(level, WinResult.noWinner("时间归零"), "§a好人阵营获胜！时间归零，好人成功存活！");
            return;
        }
        if (badRemaining <= 0 && goodRemaining > 0) {
            mode.setLastWinningFaction(BlackoutRoleManager.Faction.GOOD);
            endGame(level, WinResult.noWinner("杀手全灭"), "§a好人阵营获胜！所有杀手已被消灭");
            return;
        }
        if (goodRemaining <= 0 && badRemaining > 0) {
            mode.setLastWinningFaction(BlackoutRoleManager.Faction.BAD);
            endGame(level, WinResult.noWinner("好人全灭"), "§c杀手阵营获胜！所有好人都被淘汰了");
        }
    }

    void endGame(ServerLevel level, WinResult result, String message) {
        if (mode.isGameEnded()) return;
        mode.setGameEnded(true);
        mode.setPendingEndMessage(message);
        if (level != null) {
            try {
                var sreGame = SREGameWorldComponent.KEY.get(level);
                if (sreGame != null) {
                    sreGame.setGameStatus(SREGameWorldComponent.GameStatus.STOPPING);
                    sreGame.clearRoleMap();
                }
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("endGame: failed to stop SRE game", e);
            }
            com.habitrain.core.api.GameModeRegistry.stop(level, result);
        }
    }

    void endGame(ServerLevel level, String message) {
        mode.setLastWinningFaction(null);
        endGame(level, WinResult.forceEnd("游戏结束"), message);
    }

    void triggerSREPermanentBlackout(ServerLevel level) {
        if (level == null) return;
        var blackout = SREWorldBlackoutComponent.KEY.get(level);
        if (blackout != null) {
            blackout.triggerBlackout(true, 600000);
        }
        com.habitrain.core.game.blackout.task.RestorePowerHandler.resetCompleted(level);
        forceAssignRestorePowerToAllGood(level);
    }

    private void forceAssignRestorePowerToAllGood(ServerLevel level) {
        if (level == null) return;
        TaskManager mgr = TaskManager.getInstance();
        var restoreDef = TaskRegistry.get("habitrain_core:restore_power");
        if (restoreDef == null) return;

        for (UUID uuid : BlackoutRoleManager.getAllAlive(level)) {
            if (BlackoutRoleManager.getFaction(level, uuid) != BlackoutRoleManager.Faction.GOOD) continue;

            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(uuid);

            TaskInstance existing = mgr.getActiveTask(uuid);
            if (existing != null && !existing.isFulfilled()
                    && existing.getFullId() != null
                    && existing.getFullId().startsWith("habitrain_core:")) {
                if (sp != null) {
                    try {
                        existing.setFulfilled(true);
                        existing.getDefinition().onComplete(sp, existing);
                    } catch (Throwable t) {
                        HabiTrainCore.LOGGER.error(
                                "forceAssignRestorePowerToAllGood: failed to complete existing task {} for {}",
                                existing.getFullId(), uuid, t);
                    }
                }
            }

            mgr.removeActiveTask(uuid);
            mgr.clearBlackoutRotationFlag(uuid);

            if (sp != null) {
                try {
                    var taskComp = io.wifi.starrailexpress.cca.SREPlayerTaskComponent.KEY.get(sp);
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
        syncManager.broadcast(level, "§a供电已恢复");
    }
}
