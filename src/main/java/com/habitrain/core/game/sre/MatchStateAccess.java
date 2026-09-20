package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeIds;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.match.MatchPhase;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal CCA adapter for {@link com.habitrain.core.api.match.MatchStateApi}.
 */
public final class MatchStateAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MatchStateAccess");

    private MatchStateAccess() {}

    public static MatchPhase phase(@Nullable Level level) {
        if (level == null) {
            return MatchPhase.UNKNOWN;
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null || game.getGameStatus() == null) {
                return coreRegistryPhase(level);
            }
            MatchPhase phase = MatchPhase.fromStatusName(game.getGameStatus().name());
            // 审核 A7：经 GameModeRegistry.start() 启动的第三方模式不会改 SRE GameStatus，
            // 因此这里补一次注册表判定，否则 MatchStateApi.phase() 对它恒为 INACTIVE，
            // 「大厅卡片应拒绝」的判据会 fail-open。
            return phase == MatchPhase.INACTIVE ? coreRegistryPhase(level) : phase;
        } catch (RuntimeException e) {
            LOGGER.error("Match phase read failed; treating as UNKNOWN (not lobby)", e);
            return MatchPhase.UNKNOWN;
        }
    }

    /** {@link GameModeRegistry#hasExplicitActiveMode} 命中时按 ACTIVE 处理。 */
    private static MatchPhase coreRegistryPhase(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return MatchPhase.INACTIVE;
        }
        try {
            return GameModeRegistry.hasExplicitActiveMode(serverLevel)
                    ? MatchPhase.ACTIVE
                    : MatchPhase.INACTIVE;
        } catch (RuntimeException e) {
            LOGGER.debug("GameModeRegistry active check failed: {}", e.toString());
            return MatchPhase.INACTIVE;
        }
    }

    /**
     * 该维度的规范模式 ID；大厅期返回空串。
     *
     * <p>审核 A8：旧实现直接用 {@link GameModeRegistry#getActiveForLevel} 的被动兜底，
     * 而它就建立在上游 {@code SREGameWorldComponent.gameMode} 之上——该字段默认即谋杀且
     * 大厅期间不重置，于是「大厅里 {@code phase()==INACTIVE} 但 {@code modeId()} 恒为
     * {@code sre:murder}」自相矛盾，按「有 modeId 就套用该模式规则」的直觉写会在
     * <b>大厅</b>误用对局规则（而 {@code GameMode.isActive} 恰恰是文档明确否定的判据）。
     * 现在先取阶段，只有离开大厅才读模式。</p>
     */
    public static String modeId(@Nullable ServerLevel level) {
        if (level == null) {
            return "";
        }
        if (phase(level) == MatchPhase.INACTIVE) {
            return "";
        }
        try {
            var registered = GameModeRegistry.getActiveForLevel(level);
            if (registered.isPresent()) {
                GameMode mode = registered.get();
                if (mode != null && mode.getId() != null && !mode.getId().isBlank()) {
                    return GameModeIds.canonical(mode.getId());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("GameModeRegistry mode id failed: {}", e.toString());
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game != null && game.getGameMode() != null && game.getGameMode().identifier != null) {
                return GameModeIds.canonical(game.getGameMode().identifier.toString());
            }
        } catch (RuntimeException e) {
            LOGGER.error("SRE mode identifier read failed", e);
        }
        return "";
    }
}
