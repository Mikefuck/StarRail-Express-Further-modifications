package com.habitrain.core.api.role.v2.client;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Declarative client screen bound to one role. The physical client
 * adapter opens a stock picker / confirm / list; providers that need a
 * fully custom layout still write their own screen and only use this
 * spec as the catalog entry.
 *
 * <p>The stock client consumes these specs through
 * {@code RoleClientExtensionHooks.openRoleScreen(...)}. Providers choose the
 * gameplay trigger; the dispatcher supplies the standard picker/confirm/list
 * implementation declared by {@link #kind()}.
 */
public final class RoleScreenSpec {

    private final ResourceLocation id;
    private final String entryKey;
    private final RoleKey role;
    private final RoleScreenKind kind;
    private final String titleKey;

    private RoleScreenSpec(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.entryKey = b.entryKey;
        this.role = Objects.requireNonNull(b.role, "role");
        this.kind = b.kind;
        this.titleKey = b.titleKey;
    }

    public static Builder of(String namespace, String path) {
        return new Builder().id(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public ResourceLocation id() { return id; }
    public String entryKey() { return entryKey; }
    public RoleKey role() { return role; }
    public RoleScreenKind kind() { return kind; }
    public String titleKey() { return titleKey; }

    public static final class Builder {
        private ResourceLocation id;
        private String entryKey;
        private RoleKey role;
        private RoleScreenKind kind = RoleScreenKind.PLAYER_PICK;
        private String titleKey = "";

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

        public Builder kind(RoleScreenKind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
            return this;
        }

        public Builder titleKey(String titleKey) {
            this.titleKey = titleKey == null ? "" : titleKey;
            return this;
        }

        public RoleScreenSpec build() {
            if (id == null) {
                throw new IllegalStateException("RoleScreenSpec requires an id");
            }
            if (role == null) {
                throw new IllegalStateException("RoleScreenSpec requires a role");
            }
            return new RoleScreenSpec(this);
        }
    }
}
