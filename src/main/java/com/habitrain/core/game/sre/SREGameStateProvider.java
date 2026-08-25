package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameStateProvider;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * SRE 绑定实现的 GameStateProvider。
 * 封装 SREGameRoundEndComponent 的直接读写。
 */
public final class SREGameStateProvider implements GameStateProvider {

    public static final SREGameStateProvider INSTANCE = new SREGameStateProvider();

    private SREGameStateProvider() {}

    @Override
    public void triggerCustomWin(ServerLevel level, String customWinnerId, UUID winnerPlayerId) {
        if (level == null) return;
        SREGameWorldComponent game;
        try {
            game = SREGameWorldComponent.KEY.get(level);
        } catch (Throwable t) {
            return;
        }
        if (game == null || !allowsCustomWinWrite(game.getGameStatus())) {
            return;
        }
        SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
        if (roundEnd != null) {
            roundEnd.CustomWinnerID = customWinnerId;
            roundEnd.CustomWinnerPlayers.add(winnerPlayerId);
            roundEnd.setWinStatus(GameUtils.WinStatus.CUSTOM);
            roundEnd.sync();
        }
    }

    /** No-op when the match is already stopping/inactive or status is missing. */
    static boolean allowsCustomWinWrite(@Nullable SREGameWorldComponent.GameStatus status) {
        return status != null
                && status != SREGameWorldComponent.GameStatus.STOPPING
                && status != SREGameWorldComponent.GameStatus.INACTIVE;
    }
}
