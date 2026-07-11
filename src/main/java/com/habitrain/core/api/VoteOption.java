package com.habitrain.core.api;

/**
 * 通用选项投票中的一个候选项（非玩家 UUID，而是模式/地图等字符串 id）。
 */
public record VoteOption(String id, String displayName) {
    public VoteOption {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("option id");
        }
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
    }
}
