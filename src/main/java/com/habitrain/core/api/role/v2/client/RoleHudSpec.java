package com.habitrain.core.api.role.v2.client;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Declarative HUD widget bound to one role. Complex custom drawing stays
 * provider-side; this spec is the common, dedicated-server-safe declaration.
 */
public final class RoleHudSpec {

    private final ResourceLocation id;
    private final String entryKey;
    private final RoleKey role;
    private final RoleHudKind kind;
    private final String textKey;
    private final int color;
    private final int x;
    private final int y;
    private final boolean showWhenSpectator;

    private RoleHudSpec(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.entryKey = b.entryKey;
        this.role = Objects.requireNonNull(b.role, "role");
        this.kind = b.kind;
        this.textKey = b.textKey;
        this.color = b.color;
        this.x = b.x;
        this.y = b.y;
        this.showWhenSpectator = b.showWhenSpectator;
    }

    public static Builder of(String namespace, String path) {
        return new Builder().id(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public static Builder of(ResourceLocation id) {
        return new Builder().id(id);
    }

    public ResourceLocation id() { return id; }
    public String entryKey() { return entryKey; }
    public RoleKey role() { return role; }
    public RoleHudKind kind() { return kind; }
    public String textKey() { return textKey; }
    public int color() { return color; }
    public int x() { return x; }
    public int y() { return y; }
    public boolean showWhenSpectator() { return showWhenSpectator; }

    public static final class Builder {
        private ResourceLocation id;
        private String entryKey;
        private RoleKey role;
        private RoleHudKind kind = RoleHudKind.TEXT;
        private String textKey = "";
        private int color = 0xFFFFFF;
        private int x;
        private int y;
        private boolean showWhenSpectator;

        private Builder() {}

        public Builder id(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        public Builder entryKey(String entryKey) {
            this.entryKey = entryKey;
            return this;
        }

        public Builder role(RoleKey role) {
            this.role = Objects.requireNonNull(role, "role");
            return this;
        }

        public Builder kind(RoleHudKind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
            return this;
        }

        public Builder textKey(String textKey) {
            this.textKey = textKey == null ? "" : textKey;
            return this;
        }

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public Builder position(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder showWhenSpectator(boolean showWhenSpectator) {
            this.showWhenSpectator = showWhenSpectator;
            return this;
        }

        public RoleHudSpec build() {
            if (id == null) {
                throw new IllegalStateException("RoleHudSpec requires an id");
            }
            if (role == null) {
                throw new IllegalStateException("RoleHudSpec requires a role");
            }
            return new RoleHudSpec(this);
        }
    }
}
