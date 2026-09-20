package com.habitrain.core.game.sre;

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
