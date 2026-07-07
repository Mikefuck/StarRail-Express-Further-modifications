package com.habitrain.core.game.blackout;

import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;

class BlackoutTickCoordinator {
    private final BlackoutMode mode;
    private final BlackoutVictoryChecker victoryChecker;
    private final BlackoutSyncManager syncManager;
    private final BlackoutSheriffResolver sheriffResolver;

    private int tickAccumulator = 0;
    private boolean sreGameRunning = false;
    private boolean cachedSreActive = false;

    BlackoutTickCoordinator(BlackoutMode mode, BlackoutVictoryChecker victoryChecker,
                             BlackoutSyncManager syncManager,
                             BlackoutSheriffResolver sheriffResolver) {
        this.mode = mode;
        this.victoryChecker = victoryChecker;
        this.syncManager = syncManager;
        this.sheriffResolver = sheriffResolver;
    }

    void onSreGameStarted(ServerLevel level) {
        cachedSreActive = true;
    }

    void onSreGameEnded(ServerLevel level) {
        cachedSreActive = false;
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

            BlackoutSheriffVoteManager.tickSecond(level)
                    .ifPresent(res -> sheriffResolver.applyVoteResult(level, res));

            victoryChecker.tickSecond(level);

            if (mode.getCurrentLevel() == null || mode.isGameEnded()) return;

            if (tickAccumulator % 40 == 0 && BlackoutTimerSystem.isPermanentBlackoutActive(level)) {
                victoryChecker.reapplyPermanentBlackout(level);
            }
        }
    }
}
