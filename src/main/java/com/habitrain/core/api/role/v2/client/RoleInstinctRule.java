package com.habitrain.core.api.role.v2.client;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Declarative instinct highlight rule. Core applies it on the matching
 * {@link InstinctPhase}; first non-pass rule wins (same as upstream).
 */
public final class RoleInstinctRule {

    private final ResourceLocation id;
    private final RoleKey viewerRole;
    private final @Nullable RoleKey targetRole;
    private final InstinctPhase phase;
    private final @Nullable Integer color;
    private final boolean hide;

    private RoleInstinctRule(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.viewerRole = Objects.requireNonNull(b.viewerRole, "viewerRole");
        this.targetRole = b.targetRole;
        this.phase = b.phase;
        this.color = b.color;
        this.hide = b.hide;
    }

    public static Builder of(String namespace, String path) {
        return new Builder().id(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public static Builder of(ResourceLocation id) {
        return new Builder().id(id);
    }

    public ResourceLocation id() { return id; }
    public RoleKey viewerRole() { return viewerRole; }
    public @Nullable RoleKey targetRole() { return targetRole; }
    public InstinctPhase phase() { return phase; }
    public @Nullable Integer color() { return color; }
    public boolean hide() { return hide; }

    public static final class Builder {
        private ResourceLocation id;
        private RoleKey viewerRole;
        private @Nullable RoleKey targetRole;
        private InstinctPhase phase = InstinctPhase.ALIVE_AFTER;
        private @Nullable Integer color;
        private boolean hide;

        private Builder() {}

        public Builder id(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        public Builder viewerRole(RoleKey viewerRole) {
            this.viewerRole = Objects.requireNonNull(viewerRole, "viewerRole");
            return this;
        }

        public Builder targetRole(@Nullable RoleKey targetRole) {
            this.targetRole = targetRole;
            return this;
        }

        public Builder phase(InstinctPhase phase) {
            this.phase = Objects.requireNonNull(phase, "phase");
            return this;
        }

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public Builder hide() {
            this.hide = true;
            return this;
        }

        public RoleInstinctRule build() {
            if (id == null) {
                throw new IllegalStateException("RoleInstinctRule requires an id");
            }
            if (viewerRole == null) {
                throw new IllegalStateException("RoleInstinctRule requires a viewerRole");
            }
            if (!hide && color == null) {
                throw new IllegalStateException("RoleInstinctRule requires a color or hide()");
            }
            return new RoleInstinctRule(this);
        }
    }
}
