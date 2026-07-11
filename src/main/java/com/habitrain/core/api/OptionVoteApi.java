package com.habitrain.core.api;

import com.habitrain.core.vote.OptionVoteManager;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 通用选项投票公共 API（薄包装 {@link OptionVoteManager}）。
 */
public final class OptionVoteApi {
    private OptionVoteApi() {}

    public static boolean start(ServerLevel level, String voteId, List<VoteOption> options,
                                int durationSeconds, Consumer<VoteResult> onResolved) {
        return OptionVoteManager.start(level, voteId, "投票", "", options, durationSeconds, onResolved);
    }

    /**
     * 对当前 active 投票投/弃票（optionId null = 弃票）。
     * 网络路径应使用 {@link OptionVoteManager#cast} 并传入客户端 voteId。
     */
    public static boolean cast(ServerLevel level, UUID voter, @Nullable String optionId) {
        OptionVoteManager.cast(level, voter, OptionVoteManager.currentVoteId(level), optionId);
        return OptionVoteManager.isActive(level);
    }

    public static boolean isActive(ServerLevel level) {
        return OptionVoteManager.isActive(level);
    }

    public static void cancel(ServerLevel level) {
        OptionVoteManager.cancel(level);
    }
}
