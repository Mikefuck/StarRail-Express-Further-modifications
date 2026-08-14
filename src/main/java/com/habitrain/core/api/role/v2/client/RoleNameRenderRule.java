package com.habitrain.core.api.role.v2.client;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Declarative name-render override for one role at one {@link RoleRenderPhase}.
 */
public final class RoleNameRenderRule {

    private final ResourceLocation id;
    private final RoleKey role;
    private final RoleRenderPhase phase;
    private final boolean hide;
    private final @Nullable Integer color;

    private RoleNameRenderRule(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.role = Objects.requireNonNull(b.role, "role");
        this.phase = b.phase;
        this.hide = b.hide;
        this.color = b.color;
    }

    public static Builder of(String namespace, String path) {
        return new Builder().id(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public ResourceLocation id() { return id; }
    public RoleKey role() { return role; }
    public RoleRenderPhase phase() { return phase; }
    public boolean hide() { return hide; }
    public @Nullable Integer color() { return color; }

    public static final class Builder {
        private ResourceLocation id;
        private RoleKey role;
        private RoleRenderPhase phase = RoleRenderPhase.NAMEPLATE;
        private boolean hide;
        private @Nullable Integer color;

        private Builder() {}

        public Builder id(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        public Builder role(RoleKey role) {
            this.role = Objects.requireNonNull(role, "role");
            return this;
        }

        public Builder phase(RoleRenderPhase phase) {
            this.phase = Objects.requireNonNull(phase, "phase");
            return this;
        }

        public Builder hide() {
            this.hide = true;
            return this;
        }

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public RoleNameRenderRule build() {
            if (id == null) {
                throw new IllegalStateException("RoleNameRenderRule requires an id");
            }
            if (role == null) {
                throw new IllegalStateException("RoleNameRenderRule requires a role");
            }
            if (!hide && color == null) {
                throw new IllegalStateException("RoleNameRenderRule requires hide() or a color");
            }
            return new RoleNameRenderRule(this);
        }
    }
}
