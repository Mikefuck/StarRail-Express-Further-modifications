package com.habitrain.core.api.role.v2;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * A read-only snapshot of a player's current role and faction.
 *
 * <p><b>审核 R-15</b>：{@code faction} 在当前实现里<b>恒为 {@code null}</b>——唯一的构造点
 * （{@code RoleChangeServiceImpl}）把它写死为 null 且从不赋值。旧的「when provided by a mode」
 * 措辞会让消费方以为它有时有值。</p>
 *
 * <p>{@code role} is the canonical role key (or {@code null} if the player has no
 * live role); {@code faction} is the current mode faction name (when provided by a mode, e.g.
 * GOOD/BAD), or {@code null} when not applicable.
 */
public record RoleView(UUID playerId, @Nullable RoleKey role, @Nullable String faction) {

    public RoleView {
        Objects.requireNonNull(playerId, "playerId");
    }
}
