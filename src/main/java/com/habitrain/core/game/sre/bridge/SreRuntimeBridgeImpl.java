package com.habitrain.core.game.sre.bridge;


import com.habitrain.core.game.sre.match.MatchStateAccess;
import com.habitrain.core.game.sre.mode.ActiveModeForPlayer;
import com.habitrain.core.game.sre.mode.SREModeStartAdapter;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.match.MatchPhase;
import com.habitrain.core.api.spi.SreRuntimeBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * {@link SreRuntimeBridge} 的 SRE 实现（模式层）。
 *
 * <p>公开层不再直接调用 {@link ActiveModeForPlayer} / {@link SREModeStartAdapter} /
 * {@link MatchStateAccess}，而是经 {@code CoreSpi} 转到本类，从而让
 * {@code api → game.sre} 的编译期反向依赖归零。
 */
public final class SreRuntimeBridgeImpl implements SreRuntimeBridge {

    public static final SreRuntimeBridgeImpl INSTANCE = new SreRuntimeBridgeImpl();

    private SreRuntimeBridgeImpl() {}

    @Override public boolean reserveMatch(ServerLevel level) {
        return com.habitrain.core.game.sre.match.ServerMatchContext.GATE.start(
                com.habitrain.core.game.sre.match.ServerMatchContext.dimension(level));
    }
    @Override public void releaseMatch(ServerLevel level) {
        com.habitrain.core.game.sre.match.ServerMatchContext.release(level);
    }
    @Override public void matchStarted(ServerLevel level) {
        // SRE modes still have a countdown; their native started event owns RUNNING.
        var game = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(level);
        if (game.getGameStatus() == io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.INACTIVE) {
            com.habitrain.core.game.sre.match.ServerMatchContext.GATE.transition(
                    com.habitrain.core.game.sre.match.ServerMatchContext.dimension(level),
                    com.habitrain.core.game.sre.match.SingleMatchGate.Phase.RUNNING);
        }
    }

    @Override
    public boolean isSreGameBlocking(ServerLevel level) {
        return SREModeStartAdapter.isSreGameBlocking(level);
    }

    @Override
    public Optional<GameMode> resolveActiveForPlayer(ServerPlayer player) {
        return ActiveModeForPlayer.resolve(player);
    }

    @Override
    public MatchPhase matchPhase(@Nullable Level level) {
        return MatchStateAccess.phase(level);
    }

    @Override
    public String matchModeId(@Nullable ServerLevel level) {
        return MatchStateAccess.modeId(level);
    }
}
