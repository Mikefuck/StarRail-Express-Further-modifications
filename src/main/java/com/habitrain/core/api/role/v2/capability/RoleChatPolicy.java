package com.habitrain.core.api.role.v2.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Declarative chat policy for one role (design §16.4).
 *
 * <p><b>{@code muteReceive} is experimental (audit P1-3):</b> the current chat
 * gate uses Fabric {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE}, which only
 * sees the sender, so {@code muteSend} is enforced but {@code muteReceive}
 * (per-receiver filtering) cannot take effect yet. A policy declaring it is
 * registered and diagnosable but must not be relied on in-game until a
 * receiver-filtering adapter lands.
 */
public final class RoleChatPolicy {

    private final ResourceLocation id;
    private final RoleKey role;
    private final boolean muteSend;
    private final boolean muteReceive;

    private RoleChatPolicy(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.role = Objects.requireNonNull(b.role, "role");
        this.muteSend = b.muteSend;
        this.muteReceive = b.muteReceive;
    }

    public static Builder of(String namespace, String path) {
        return new Builder().id(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public static Builder of(ResourceLocation id) {
        return new Builder().id(id);
    }

    public ResourceLocation id() { return id; }
    public RoleKey role() { return role; }
    public boolean muteSend() { return muteSend; }
    public boolean muteReceive() { return muteReceive; }

    public static final class Builder {
        private ResourceLocation id;
        private RoleKey role;
        private boolean muteSend;
        private boolean muteReceive;

        private Builder() {}

        public Builder id(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        public Builder role(RoleKey role) {
            this.role = Objects.requireNonNull(role, "role");
            return this;
        }

        public Builder muteSend() {
            this.muteSend = true;
            return this;
        }

        /** @see RoleChatPolicy muteReceive experimental note (audit P1-3) */
        public Builder muteReceive() {
            this.muteReceive = true;
            return this;
        }

        public RoleChatPolicy build() {
            if (id == null) {
                throw new IllegalStateException("RoleChatPolicy requires an id");
            }
            if (role == null) {
                throw new IllegalStateException("RoleChatPolicy requires a role");
            }
            return new RoleChatPolicy(this);
        }
    }
}