package com.habitrain.core.api;

import java.util.List;

/**
 * Optional overrides for a single mode→map vote run.
 * Durations of {@code -1} (or &lt;= 0) mean "use {@code ModeMapVoteSettings}".
 * Null mode/map id lists mean unrestricted (still filtered by config enables / allowedMaps).
 *
 * <p><b>审核 B2</b>：这些字段是公开可变的，而运行期投票会话会直接持有本对象的引用。
 * 现在 {@code ModeMapVoteOrchestrator.start(...)} 会在入口处做一次不可变快照，
 * 因此 {@code start} 返回后再修改本对象<b>不会</b>影响进行中的投票。
 * 快照语义见 {@link #snapshot()}。</p>
 */
public final class ModeMapVoteConfig {
    public int modeDurationSeconds = -1;
    public int mapDurationSeconds = -1;
    /** null = all registered modes (after config enable filter) */
    public List<String> modeIds;
    /** null = all available maps (after enable / mode whitelist filter) */
    public List<String> mapIds;

    public ModeMapVoteConfig() {
    }

    private ModeMapVoteConfig(int modeDurationSeconds, int mapDurationSeconds,
                              List<String> modeIds, List<String> mapIds) {
        this.modeDurationSeconds = modeDurationSeconds;
        this.mapDurationSeconds = mapDurationSeconds;
        this.modeIds = modeIds;
        this.mapIds = mapIds;
    }

    /**
     * 返回一份不可变快照：{@code modeIds}/{@code mapIds} 被复制为只读列表，
     * {@code null} 仍然表示「不限制」。
     */
    public ModeMapVoteConfig snapshot() {
        return new ModeMapVoteConfig(
                modeDurationSeconds,
                mapDurationSeconds,
                modeIds == null ? null : List.copyOf(modeIds),
                mapIds == null ? null : List.copyOf(mapIds));
    }
}
