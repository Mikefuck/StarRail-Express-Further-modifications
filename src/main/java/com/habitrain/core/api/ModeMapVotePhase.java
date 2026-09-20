package com.habitrain.core.api;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 模式→地图两阶段投票的阶段。
 *
 * <p>审核 B7/M-24：旧版 {@link ModeMapVoteSnapshot#phase()} 是裸 {@code String}，
 * 取值只能靠 javadoc 约束，下游拼错不报错、也没有编译期穷尽检查。
 * 现在公开本枚举，{@link ModeMapVoteSnapshot#phase()} 返回它。</p>
 */
public enum ModeMapVotePhase {
    /** 没有进行中的投票。 */
    IDLE,
    /** 第一阶段：投票选择游戏模式。 */
    MODE_VOTING,
    /** 第二阶段：投票选择地图。 */
    MAP_VOTING,
    /** 地图已选定，正在切换/加载地图。 */
    SWITCHING_MAP,
    /** 地图已就绪，正在启动游戏模式。 */
    STARTING_MODE;

    /** 是否处于「投票进行中」的两个阶段。 */
    public boolean isVoting() {
        return this == MODE_VOTING || this == MAP_VOTING;
    }

    public boolean isIdle() {
        return this == IDLE;
    }

    /** 解析阶段名；无法识别时返回 {@link #IDLE}（fail-safe：视为没有投票）。 */
    public static ModeMapVotePhase fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return IDLE;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return IDLE;
        }
    }
}
