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
 * <p><b>线程契约</b>（审核 B16）：所有方法都必须在<b>服务端主线程</b>调用。</p>
 */
public final class OptionVoteApi {
    public static final int MAX_OPTIONS = 64;
    public static final int MAX_ID_LENGTH = 64;
    /** API-TV-19：选项展示名称网络层最大长度（支持长中文地图名 + 人数不推荐标记）。 */
    public static final int MAX_DISPLAY_NAME_LENGTH = 128;
    public static final int MAX_TITLE_LENGTH = 128;
    public static final int MAX_DESCRIPTION_LENGTH = 256;

    private OptionVoteApi() {}

    /** API-TV-05：判断是否为核心内部保留的 Mode/Map 投票通道 ID。 */
    private static boolean isReservedVoteId(@Nullable String voteId) {
        if (voteId == null) {
            return false;
        }
        String trimmed = voteId.trim();
        return "mode".equalsIgnoreCase(trimmed) || "map".equalsIgnoreCase(trimmed);
    }

    public static boolean start(ServerLevel level, String voteId, List<VoteOption> options,
                                int durationSeconds, Consumer<VoteResult> onResolved) {
        return start(level, voteId, "投票", "", options, durationSeconds, onResolved);
    }

    public static boolean start(ServerLevel level, String voteId, @Nullable String title,
                                @Nullable String description, List<VoteOption> options,
                                int durationSeconds, Consumer<VoteResult> onResolved) {
        if (voteId == null || voteId.isBlank() || isReservedVoteId(voteId)) {
            return false;
        }
        return CoreSpi.vote().optionStart(
                level,
                voteId,
                title != null ? title : "投票",
                description != null ? description : "",
                options,
                durationSeconds,
                onResolved);
    }

    /**
     * 对当前外部发起的 active 投票投/弃票（optionId null = 弃票）。
     *
     * <p>API-TV-05：若当前活跃投票是核心内部保留的 {@code "mode"} / {@code "map"} 阶段，
     * 公开 API 拒绝篡改选票。</p>
     */
    public static boolean cast(ServerLevel level, UUID voter, @Nullable String optionId) {
        String currentId = CoreSpi.vote().optionCurrentVoteId(level);
        if (currentId == null || currentId.isBlank() || isReservedVoteId(currentId)) {
            return false;
        }
        return CoreSpi.vote().optionCast(level, voter, currentId, optionId);
    }

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
     * 取消当前外部发起的选项投票。
     *
     * <p>API-TV-05：若当前活跃投票是核心内部保留的 {@code "mode"} / {@code "map"} 阶段，
     * 请使用 {@link ModeMapVoteApi#cancel(ServerLevel)}；本方法不跨层中断内部两阶段投票。</p>
     */
    public static void cancel(ServerLevel level) {
        String currentId = CoreSpi.vote().optionCurrentVoteId(level);
        if (currentId == null || currentId.isBlank() || isReservedVoteId(currentId)) {
            return;
        }
        CoreSpi.vote().optionCancel(level);
    }
}
