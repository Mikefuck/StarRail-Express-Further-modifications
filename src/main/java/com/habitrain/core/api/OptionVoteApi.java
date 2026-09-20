package com.habitrain.core.api;

import com.habitrain.core.api.spi.CoreSpi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 通用选项投票公共 API。
 * 服务端发起/查询/取消投票；网络 C2S 在内部自行携带 voteId，不经过 {@link #cast(ServerLevel, UUID, String)}。
 *
 * <p><b>线程契约</b>（审核 B16）：所有方法都必须在<b>服务端主线程</b>调用。实现内部用
 * 非并发容器保存候选人与会话状态，异步线程调用会与主线程 tick 并发改写集合。
 */
public final class OptionVoteApi {
    private OptionVoteApi() {}

    /**
     * 发起一次选项投票（默认标题「投票」、无描述）。
     *
     * <p>审核 C8：底层 {@code OptionVoteManager.start} 一直支持自定义标题与描述，
     * 但公开层把它们写死。需要自定义请用
     * {@link #start(ServerLevel, String, String, String, List, int, Consumer)}。</p>
     */
    public static boolean start(ServerLevel level, String voteId, List<VoteOption> options,
                                int durationSeconds, Consumer<VoteResult> onResolved) {
        return start(level, voteId, "投票", "", options, durationSeconds, onResolved);
    }

    /**
     * 发起一次选项投票（自定义标题与描述）。
     *
     * <p>{@code options} 的 {@code id} 必须非空、长度 ≤ 64、且互不重复；{@code durationSeconds ≥ 1}。
     * 任一不满足都会在改动任何状态<b>之前</b>返回 {@code false}（审核 B17）。</p>
     *
     * @param title       投票界面标题，{@code null} 回退「投票」
     * @param description 投票界面描述，{@code null} 回退空串
     * @return 是否成功开始
     */
    public static boolean start(ServerLevel level, String voteId, @Nullable String title,
                                @Nullable String description, List<VoteOption> options,
                                int durationSeconds, Consumer<VoteResult> onResolved) {
        // 审核 M-19："mode"/"map" 是编排器保留的 voteId，第三方用它们会意外继承
        // 维修锁图过滤并与编排器抢占唯一的投票位，因此公开入口显式拒绝。
        if (voteId != null && (voteId.equals("mode") || voteId.equals("map"))) {
            return false;
        }
        return CoreSpi.vote().optionStart(level, voteId, title, description, options, durationSeconds, onResolved);
    }

    /**
     * 对当前 active 投票投/弃票（optionId null = 弃票）。
     * {@code voter} 是玩家 UUID。网络 C2S 路径在内部自行提供 voteId，不经过本方法。
     */
    public static boolean cast(ServerLevel level, UUID voter, @Nullable String optionId) {
        return CoreSpi.vote().optionCast(level, voter, CoreSpi.vote().optionCurrentVoteId(level), optionId);
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
        return CoreSpi.vote().optionIsActive(level);
    }

    /**
     * 取消当前投票。
     *
     * <p>审核 B16：取消会以 {@link VoteResult#cancelled()} 回调
     * {@code onResolved}（若发起时提供了），调用方不会再遇到「投票凭空消失、
     * 状态机悬挂」的情况。</p>
     */
    public static void cancel(ServerLevel level) {
        CoreSpi.vote().optionCancel(level);
    }
}
