package com.habitrain.core.api.role.v2.client;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;
import java.util.UUID;

/** A stable selection ID, display label and optional player identity. */
public record RoleScreenOption(String id, Component label, @Nullable UUID playerId) {
    public RoleScreenOption {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Screen option id must not be blank");
        label = Objects.requireNonNull(label, "label").copy();
    }
    @Override public Component label() { return label.copy(); }
    public static RoleScreenOption player(UUID playerId, Component label) {
        return new RoleScreenOption(Objects.requireNonNull(playerId, "playerId").toString(), label, playerId);
    }
    public static RoleScreenOption item(String id, Component label) { return new RoleScreenOption(id, label, null); }
}
