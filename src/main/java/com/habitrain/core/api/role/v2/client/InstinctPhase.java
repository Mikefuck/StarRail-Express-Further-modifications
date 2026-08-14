package com.habitrain.core.api.role.v2.client;

/**
 * Upstream instinct highlight phase (design §16.3).
 *
 * <p>Maps onto {@code CommonInstinctEvents} / {@code OnGetInstinctHighlight}:
 * before / middle / after for living viewers, plus spectator.
 */
public enum InstinctPhase {
    ALIVE_BEFORE,
    ALIVE_MIDDLE,
    ALIVE_AFTER,
    SPECTATOR
}
