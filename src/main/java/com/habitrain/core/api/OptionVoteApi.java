package com.habitrain.core.api;

import com.habitrain.core.vote.OptionVoteManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 通用选项投票公共 API。
 * 服务端发起/查询/取消投票；网络 C2S 在内部自行携带 voteId，不经过 {@link #cast(ServerLevel, UUID, String)}。
 */
public final class OptionVoteApi {
    private OptionVoteApi() {}

    public static boolean start(ServerLevel level, String voteId, List<VoteOption> options,
                                int durationSeconds, Consumer<VoteResult> onResolved) {
        return OptionVoteManager.start(level, voteId, "投票", "", options, durationSeconds, onResolved);
    }

    /**
     * 对当前 active 投票投/弃票（optionId null = 弃票）。
     * {@code voter} 是玩家 UUID。网络 C2S 路径在内部自行提供 voteId，不经过本方法。
     */
    public static boolean cast(ServerLevel level, UUID voter, @Nullable String optionId) {
        return OptionVoteManager.cast(level, voter, OptionVoteManager.currentVoteId(level), optionId);
    }

    /**
     * Same as {@link #cast(ServerLevel, UUID, String)} using the player's UUID.
     */
    public static boolean cast(ServerLevel level, ServerPlayer voter, @Nullable String optionId) {
        if (voter == null) {
            return false;
        }
        return cast(level, voter.getUUID(), optionId);
    }

    public static boolean isActive(ServerLevel level) {
        return OptionVoteManager.isActive(level);
    }

    public static void cancel(ServerLevel level) {
        OptionVoteManager.cancel(level);
    }
}
