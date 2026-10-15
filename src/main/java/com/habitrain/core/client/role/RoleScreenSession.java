package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.client.*;
import org.jetbrains.annotations.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Selection state independent of rendering; completion is consumed at most once. */
public final class RoleScreenSession {
    private final RoleScreenKind kind;
    private final List<RoleScreenOption> options;
    private @Nullable RoleScreenOption selected;
    private boolean completed;

    public RoleScreenSession(RoleScreenKind kind, List<RoleScreenOption> options) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.options = List.copyOf(options);
        if (kind == RoleScreenKind.CONFIRM && !options.isEmpty()) {
            throw new IllegalArgumentException("CONFIRM screens do not accept options");
        }
        var ids = new HashSet<String>();
        var players = new HashSet<java.util.UUID>();
        for (var option : this.options) {
            if (!ids.add(option.id())) throw new IllegalArgumentException("Duplicate screen option: " + option.id());
            if (kind == RoleScreenKind.PLAYER_PICK
                    && (option.playerId() == null || !players.add(option.playerId()))) {
                throw new IllegalArgumentException("Player options require unique UUIDs");
            }
        }
    }

    public List<RoleScreenOption> options() { return options; }
    public RoleScreenKind kind() { return kind; }
    public @Nullable String selectedId() { return selected == null ? null : selected.id(); }
    public boolean canConfirm() { return !completed && (kind == RoleScreenKind.CONFIRM || selected != null); }
    public boolean select(String id) {
        if (completed) return false;
        for (var option : options) {
            if (option.id().equals(id)) { selected = option; return true; }
        }
        return false;
    }
    public @Nullable RoleScreenResult confirm() {
        if (!canConfirm()) return null;
        completed = true;
        return kind == RoleScreenKind.CONFIRM ? RoleScreenResult.confirmed() : RoleScreenResult.selected(selected);
    }
    public @Nullable RoleScreenResult cancel() {
        if (completed) return null;
        completed = true;
        return RoleScreenResult.cancelled();
    }
}
