package com.habitrain.core.api;

import com.habitrain.core.api.spi.CoreSpi;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Public API for the two-phase mode→map lobby vote.
 *
 * <p><b>线程契约</b>（审核 B16）：所有方法都必须在<b>服务端主线程</b>调用。实现内部用
 * 非并发容器保存会话状态，异步线程调用会与主线程 tick 并发改写集合。
 */
public final class ModeMapVoteApi {
    private ModeMapVoteApi() {}

    public static boolean start(ServerLevel level) {
        return start(level, new ModeMapVoteConfig());
    }

    /**
     * 发起模式→地图两阶段投票。
     *
     * @return 是否真的开始了投票。审核 C7/M-13：{@code false} 有 6 种原因
     *     （level 为空 / 配置未启用 / 已有投票 / 选项投票占用 / SRE 占用 / 模式占用 / 无候选），
     *     调用方若需要区分请用 {@link #lastFailure(ServerLevel)}。
     */
    public static boolean start(ServerLevel level, ModeMapVoteConfig config) {
        return CoreSpi.vote().modeMapStart(level, config != null ? config : new ModeMapVoteConfig());
    }

    /**
     * 最近一次失败原因的可读描述；没有失败记录时返回空串。
     * 每次 {@link #start} 都会覆盖它。
     */
    public static String lastFailure(ServerLevel level) {
        return CoreSpi.vote().modeMapLastFailure(level);
    }

    public static boolean cancel(ServerLevel level) {
        return CoreSpi.vote().modeMapCancel(level);
    }

    public static boolean isRunning(ServerLevel level) {
        return CoreSpi.vote().modeMapIsRunning(level);
    }

    public static Optional<ModeMapVoteSnapshot> getSnapshot(ServerLevel level) {
        return Optional.ofNullable(CoreSpi.vote().modeMapSnapshot(level));
    }
}
