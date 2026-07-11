package com.habitrain.core.api;

import java.util.List;

/**
 * Optional overrides for a single mode→map vote run.
 * Durations of {@code -1} (or &lt;= 0) mean "use {@code ModeMapVoteSettings}".
 * Null mode/map id lists mean unrestricted (still filtered by config enables / allowedMaps).
 */
public final class ModeMapVoteConfig {
    public int modeDurationSeconds = -1;
    public int mapDurationSeconds = -1;
    /** null = all registered modes (after config enable filter) */
    public List<String> modeIds;
    /** null = all available maps (after enable / mode whitelist filter) */
    public List<String> mapIds;
}
