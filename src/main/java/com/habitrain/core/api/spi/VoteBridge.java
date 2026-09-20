package com.habitrain.core.api.spi;

import com.habitrain.core.api.ModeMapVoteConfig;
import com.habitrain.core.api.ModeMapVoteSnapshot;
import com.habitrain.core.api.VoteOption;
import com.habitrain.core.api.VoteResult;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 投票组桥接接口（SPI）。
 *
 * <p>对应实现 {@code vote.OptionVoteManager} 与 {@code vote.ModeMapVoteOrchestrator}
 * （均为静态方法，故由薄适配器 {@code vote.VoteBridgeImpl} 装配）。去掉
 * {@code api → vote} 反向依赖（审核 A2）。
 *
 * <p>未装配时一律返回「失败 / 不存在」，公开层不会 NPE。
 */
public interface VoteBridge {

    // ==================== 通用选项投票 ====================

    default boolean optionStart(ServerLevel level,
                                String voteId,
                                String title,
                                String description,
                                List<VoteOption> options,
                                int durationSeconds,
                                Consumer<VoteResult> onResolved) {
        return false;
    }

    default boolean optionCast(ServerLevel level, UUID voter, String voteId, @Nullable String optionId) {
        return false;
    }

    default boolean optionIsActive(ServerLevel level) {
        return false;
    }

    default void optionCancel(ServerLevel level) {
    }

    default String optionCurrentVoteId(ServerLevel level) {
        return "";
    }

    // ==================== 模式 → 地图两阶段投票 ====================

    default boolean modeMapStart(ServerLevel level, ModeMapVoteConfig config) {
        return false;
    }

    default boolean modeMapCancel(ServerLevel level) {
        return false;
    }

    /** 最近一次 {@link #modeMapStart} 失败的可读原因；没有失败记录时返回空串。 */
    default String modeMapLastFailure(ServerLevel level) {
        return "";
    }

    default boolean modeMapIsRunning(ServerLevel level) {
        return false;
    }

    default ModeMapVoteSnapshot modeMapSnapshot(ServerLevel level) {
        return null;
    }
}
