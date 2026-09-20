package com.habitrain.core.api.match;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeIds;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.spi.CoreSpi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Public match-phase / mode-id queries. Do not use {@link GameMode#isActive}
 * to decide lobby vs in-round — leftover mode objects can stay true in the lobby.
 *
 * <p>实现经 {@link CoreSpi} 的 SRE 运行时桥接取得，公开层不依赖 {@code game.sre}。
 */
public final class MatchStateApi {
    private MatchStateApi() {}

    public static MatchPhase phase(@Nullable Level level) {
        return CoreSpi.matchPhase(level);
    }

    /** True when status is not {@link MatchPhase#INACTIVE} (cards must refuse). */
    public static boolean hasLeftLobby(@Nullable Level level) {
        return phase(level).hasLeftLobby();
    }

    /**
     * Canonical short mode id for the level, or empty when the level is in the
     * lobby or no SRE/core mode is readable.
     *
     * <p>审核 A8：旧实现总能在<b>大厅</b>返回残留的 {@code sre:murder}，与
     * {@link #phase} 返回 {@code INACTIVE} 自相矛盾——按「有 modeId 就套用该模式配置」
     * 的直觉写会在 lobby 误用对局内规则。现在只有离开大厅后才会返回非空值，
     * <b>但空串仍不等于「本局没有模式」</b>（读不到时也为空串），
     * 因此不要把它当锁定判据；判定大厅请用 {@link #phase}。</p>
     *
     * <p>取值优先级：{@link GameModeRegistry#getActiveForLevel} → SRE {@code identifier}，
     * 并把 {@code sre:blackout} 映射为 {@link GameModeIds#BLACKOUT}。</p>
     */
    public static String modeId(@Nullable ServerLevel level) {
        return CoreSpi.matchModeId(level);
    }
}
