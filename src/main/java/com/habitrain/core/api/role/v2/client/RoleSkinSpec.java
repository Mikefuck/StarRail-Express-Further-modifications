package com.habitrain.core.api.role.v2.client;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Declarative skin for one role and slot. Dedicated-server-safe: only
 * {@link ResourceLocation}s, no client renderer types.
 */
public final class RoleSkinSpec {

    private final ResourceLocation id;
    private final String entryKey;
    private final RoleKey role;
    private final RoleSkinKind kind;
    private final @Nullable ResourceLocation wideTexture;
    private final @Nullable ResourceLocation slimTexture;

    private RoleSkinSpec(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.entryKey = b.entryKey;
        this.role = Objects.requireNonNull(b.role, "role");
        this.kind = b.kind;
        this.wideTexture = b.wideTexture;
        this.slimTexture = b.slimTexture;
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
    public RoleSkinKind kind() { return kind; }
    public @Nullable ResourceLocation wideTexture() { return wideTexture; }
    public @Nullable ResourceLocation slimTexture() { return slimTexture; }

    public @Nullable ResourceLocation texture(boolean slim) {
        if (slim && slimTexture != null) {
            return slimTexture;
        }
        return wideTexture != null ? wideTexture : slimTexture;
    }

    public static final class Builder {
        private ResourceLocation id;
        private String entryKey;
        private RoleKey role;
        private RoleSkinKind kind = RoleSkinKind.NORMAL;
        private @Nullable ResourceLocation wideTexture;
        private @Nullable ResourceLocation slimTexture;

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

        public Builder kind(RoleSkinKind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
            return this;
        }

        public Builder wide(ResourceLocation texture) {
            this.wideTexture = texture;
            return this;
        }

        public Builder slim(ResourceLocation texture) {
            this.slimTexture = texture;
            return this;
        }

        public Builder texture(ResourceLocation texture) {
            this.wideTexture = texture;
            this.slimTexture = texture;
            return this;
        }

        public RoleSkinSpec build() {
            if (id == null) {
                throw new IllegalStateException("RoleSkinSpec requires an id");
            }
            if (role == null) {
                throw new IllegalStateException("RoleSkinSpec requires a role");
            }
            if (wideTexture == null && slimTexture == null) {
                throw new IllegalStateException("RoleSkinSpec requires a texture");
            }
            return new RoleSkinSpec(this);
        }
    }
}
