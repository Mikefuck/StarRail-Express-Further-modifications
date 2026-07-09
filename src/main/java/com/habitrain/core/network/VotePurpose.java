package com.habitrain.core.network;

/**
 * 通用投票类型枚举。
 * <p>用于 {@link BlackoutVotePayload} 和 {@link BlackoutVoteCastPayload} 的 purpose 字段，
 * 替代裸字符串字面量，提供编译期类型安全。
 */
public enum VotePurpose {
    EXILE,
    SHERIFF;

    /**
     * 返回枚举常量的名称，与网络传输层使用的字符串值一致。
     */
    @Override
    public String toString() {
        return name();
    }

    /**
     * 从字符串安全解析，不匹配时返回 null。
     */
    public static VotePurpose fromString(String value) {
        for (VotePurpose p : values()) {
            if (p.name().equals(value)) return p;
        }
        return null;
    }
}
