package com.habitrain.core.game.blackout;

import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;

class BlackoutTickCoordinator {
    private final BlackoutMode mode;
    private final BlackoutVictoryChecker victoryChecker;
    private final BlackoutSyncManager syncManager;

    private int tickAccumulator = 0;
    private boolean sreGameRunning = false;
    private boolean cachedSreActive = false;

    BlackoutTickCoordinator(BlackoutMode mode, BlackoutVictoryChecker victoryChecker,
                             BlackoutSyncManager syncManager) {
        this.mode = mode;
        this.victoryChecker = victoryChecker;
        this.syncManager = syncManager;
    }

    void onPreStart() {
        tickAccumulator = 0;
        sreGameRunning = false;
        cachedSreActive = false;
    }

    void tick(ServerLevel level) {
        if (level != mode.getCurrentLevel() || mode.isGameEnded()) return;

        if (tickAccumulator % 20 == 0 || !cachedSreActive) {
            var sreGame = SREGameWorldComponent.KEY.get(level);
            cachedSreActive = sreGame != null && sreGame.isRunning();
        }

        if (cachedSreActive && !sreGameRunning) {
            sreGameRunning = true;
            // 通知警察聘请服务：SRE 游戏实际开始运行，记录计时起点
            BlackoutPoliceHireService.onGameStarted(level);
        }

        if (!cachedSreActive && sreGameRunning) {
            sreGameRunning = false;
            victoryChecker.endGame(level, "游戏结束");
            return;
        }

        if (!cachedSreActive) return;

        tickAccumulator++;
        if (tickAccumulator % 20 == 0) {
            BlackoutTimerSystem.tickSecond(level);

            syncManager.tickSecond(level);

            // Auto sheriff vote removed — police hire and exile vote ticked separately.
            BlackoutExileVoteManager.tickSecond(level);

            victoryChecker.tickSecond(level);

            if (mode.getCurrentLevel() == null || mode.isGameEnded()) return;

            if (tickAccumulator % 40 == 0 && BlackoutTimerSystem.isPermanentBlackoutActive(level)) {
                victoryChecker.reapplyPermanentBlackout(level);
            }
        }
    }
}
