package com.habitrain.core.api.role.v2.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Declarative voice-chat policy for one role (design §16.4).
 *
 * <p>Core only stores the semantic. A voice-chat adapter, when present,
 * consults {@link RoleCapabilityApi#evaluateVoice}; without the mod the
 * policy is still registered and evaluable, but no external class loads.
 */
public final class RoleVoicePolicy {

    private final ResourceLocation id;
    private final RoleKey role;
    private final boolean muteSend;
    private final boolean muteReceive;
    private final boolean isolateGroup;
    private final boolean hearWorld;
    private final double maxDistance;

    private RoleVoicePolicy(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.role = Objects.requireNonNull(b.role, "role");
        this.muteSend = b.muteSend;
        this.muteReceive = b.muteReceive;
        this.isolateGroup = b.isolateGroup;
        this.hearWorld = b.hearWorld;
        this.maxDistance = b.maxDistance;
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
    public boolean isolateGroup() { return isolateGroup; }
    public boolean hearWorld() { return hearWorld; }
    public double maxDistance() { return maxDistance; }

    public static final class Builder {
        private ResourceLocation id;
        private RoleKey role;
        private boolean muteSend;
        private boolean muteReceive;
        private boolean isolateGroup;
        private boolean hearWorld = true;
        private double maxDistance;

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

        public Builder muteReceive() {
            this.muteReceive = true;
            return this;
        }

        /**
         * Marks the role as isolated. Combined with {@code hearWorld(false)}, an
         * isolated listener blocks speakers outside its own group.
         */
        public Builder isolateGroup() {
            this.isolateGroup = true;
            return this;
        }

        /** Whether an isolated listener still hears the world channel (default true). */
        public Builder hearWorld(boolean hearWorld) {
            this.hearWorld = hearWorld;
            return this;
        }

        public Builder maxDistance(double maxDistance) {
            this.maxDistance = maxDistance;
            return this;
        }

        public RoleVoicePolicy build() {
            if (id == null) {
                throw new IllegalStateException("RoleVoicePolicy requires an id");
            }
            if (role == null) {
                throw new IllegalStateException("RoleVoicePolicy requires a role");
            }
            return new RoleVoicePolicy(this);
        }
    }
}
