package com.habitrain.core.api.role.v2.client;

import org.jetbrains.annotations.Nullable;

/**
 * Pure instinct decision used by the resolver and unit tests.
 * The client adapter maps this onto {@code TrueFalseAndCustomResult}.
 */
public record InstinctDecision(Kind kind, @Nullable Integer color) {

    public enum Kind { PASS, CUSTOM, HIDE }

    public static InstinctDecision pass() {
        return new InstinctDecision(Kind.PASS, null);
    }

    public static InstinctDecision custom(int color) {
        return new InstinctDecision(Kind.CUSTOM, color);
    }

    public static InstinctDecision hide() {
        return new InstinctDecision(Kind.HIDE, null);
    }

    public boolean isPass() {
        return kind == Kind.PASS;
    }
}
