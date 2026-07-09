package com.habitrain.core.game.blackout.sre;

import com.habitrain.core.api.SREGameLauncher;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * SRE 停电游戏启动器。
 * 封装 SREGameModes、GameUtils 的直接调用，
 * 供 {@link com.habitrain.core.game.blackout.BlackoutMode} 通过接口依赖。
 */
public final class SREBlackoutGameLauncher implements SREGameLauncher {

    public static final SREBlackoutGameLauncher INSTANCE = new SREBlackoutGameLauncher();

    private static final ResourceLocation BLACKOUT_MODE_ID =
            ResourceLocation.fromNamespaceAndPath("sre", "blackout");

    private SREBlackoutGameLauncher() {}

    @Override
    public void startBlackoutGame(ServerLevel level) {
        var sreMode = SREGameModes.GAME_MODES.get(BLACKOUT_MODE_ID);
        if (sreMode == null) {
            com.habitrain.core.HabiTrainCore.LOGGER.error("SREBlackoutGameMode not found!");
            return;
        }
        var sreGame = SREGameWorldComponent.KEY.get(level);
        if (sreGame != null && !sreGame.isRunning()) {
            GameUtils.startGame(level, sreMode,
                    GameConstants.getInTicks(
                            ((io.wifi.starrailexpress.api.GameMode) sreMode).defaultStartTime, 0));
        }
    }
}
