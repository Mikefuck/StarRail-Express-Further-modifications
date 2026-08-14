package com.habitrain.core.api.role.v2.action;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Schema for one namespaced role action (design §16.2).
 *
 * <p>Providers register a spec during the registration phase. Core owns the
 * multiplex payload, size/rate/cooldown gates and server-thread dispatch.
 * Distance / line-of-sight / item checks are declared here so later
 * increments can honour them; this cut enforces size, rate, cooldown,
 * direction, current-role and (optional) alive.
 */
public final class RoleActionSpec {

    private final ResourceLocation id;
    private final RoleKey role;
    private final RoleActionDirection direction;
    private final int maxBytes;
    private final int ratePerSecond;
    private final int cooldownTicks;
    private final boolean requireCurrentRole;
    private final boolean requireAlive;
    private final boolean requireTargetAlive;
    private final double maxDistance;
    private final boolean requireLineOfSight;
    private final ActionTargetCodec targetDecoder;
    private final @Nullable RoleActionHandler handler;

    private RoleActionSpec(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.role = Objects.requireNonNull(b.role, "role");
        this.direction = b.direction;
        this.maxBytes = b.maxBytes;
        this.ratePerSecond = b.ratePerSecond;
        this.cooldownTicks = b.cooldownTicks;
        this.requireCurrentRole = b.requireCurrentRole;
        this.requireAlive = b.requireAlive;
        this.requireTargetAlive = b.requireTargetAlive;
        this.maxDistance = b.maxDistance;
        this.requireLineOfSight = b.requireLineOfSight;
        this.targetDecoder = b.targetDecoder;
        this.handler = b.handler;
    }

    public static Builder of(String namespace, String path) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        return new Builder().id(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public static Builder of(ResourceLocation id) {
        return new Builder().id(id);
    }

    public ResourceLocation id() { return id; }
    public RoleKey role() { return role; }
    public RoleActionDirection direction() { return direction; }
    public int maxBytes() { return maxBytes; }
    public int ratePerSecond() { return ratePerSecond; }
    public int cooldownTicks() { return cooldownTicks; }
    public boolean requireCurrentRole() { return requireCurrentRole; }
    public boolean requireAlive() { return requireAlive; }
    public boolean requireTargetAlive() { return requireTargetAlive; }
    public double maxDistance() { return maxDistance; }
    public boolean requireLineOfSight() { return requireLineOfSight; }
    public ActionTargetCodec targetDecoder() { return targetDecoder; }
    public @Nullable RoleActionHandler handler() { return handler; }

    public static final class Builder {
        private ResourceLocation id;
        private RoleKey role;
        private RoleActionDirection direction = RoleActionDirection.C2S;
        private int maxBytes = 256;
        private int ratePerSecond = 5;
        private int cooldownTicks = 0;
        private boolean requireCurrentRole = true;
        private boolean requireAlive = true;
        private boolean requireTargetAlive;
        private double maxDistance = 0;
        private boolean requireLineOfSight;
        private ActionTargetCodec targetDecoder = ActionTargetCodec.NONE;
        private @Nullable RoleActionHandler handler;

        private Builder() {}

        public Builder id(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        public Builder role(RoleKey role) {
            this.role = Objects.requireNonNull(role, "role");
            return this;
        }

        public Builder direction(RoleActionDirection direction) {
            this.direction = Objects.requireNonNull(direction, "direction");
            return this;
        }

        public Builder maxBytes(int maxBytes) {
            if (maxBytes < 0) {
                throw new IllegalArgumentException("maxBytes must be >= 0");
            }
            this.maxBytes = maxBytes;
            return this;
        }

        public Builder ratePerSecond(int ratePerSecond) {
            if (ratePerSecond < 1) {
                throw new IllegalArgumentException("ratePerSecond must be >= 1");
            }
            this.ratePerSecond = ratePerSecond;
            return this;
        }

        public Builder cooldownTicks(int cooldownTicks) {
            if (cooldownTicks < 0) {
                throw new IllegalArgumentException("cooldownTicks must be >= 0");
            }
            this.cooldownTicks = cooldownTicks;
            return this;
        }

        public Builder requireCurrentRole(boolean requireCurrentRole) {
            this.requireCurrentRole = requireCurrentRole;
            return this;
        }

        public Builder requireAlive(boolean requireAlive) {
            this.requireAlive = requireAlive;
            return this;
        }

        /**
         * Requires a decoded {@link ActionTargetCodec#PLAYER_UUID} target to be
         * alive (not dead / spectator). Only meaningful with a PLAYER_UUID target.
         */
        public Builder requireTargetAlive(boolean requireTargetAlive) {
            this.requireTargetAlive = requireTargetAlive;
            return this;
        }

        public Builder maxDistance(double maxDistance) {
            if (maxDistance < 0) {
                throw new IllegalArgumentException("maxDistance must be >= 0");
            }
            this.maxDistance = maxDistance;
            return this;
        }

        public Builder requireLineOfSight(boolean requireLineOfSight) {
            this.requireLineOfSight = requireLineOfSight;
            return this;
        }

        /**
         * Declares the structured target scheme (fix-doc §12.3). Distance /
         * line-of-sight checks are only allowed with
         * {@link ActionTargetCodec#PLAYER_UUID}; the platform decodes a
         * verified target into the {@link RoleActionContext}.
         */
        public Builder targetDecoder(ActionTargetCodec targetDecoder) {
            this.targetDecoder = Objects.requireNonNull(targetDecoder, "targetDecoder");
            return this;
        }

        public Builder handler(RoleActionHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public RoleActionSpec build() {
            if (id == null) {
                throw new IllegalStateException("RoleActionSpec requires an id");
            }
            if (role == null) {
                throw new IllegalStateException("RoleActionSpec requires a role");
            }
            if (direction != RoleActionDirection.S2C && handler == null) {
                throw new IllegalStateException("C2S/BIDIRECTIONAL RoleActionSpec requires a handler");
            }
            if ((maxDistance > 0 || requireLineOfSight || requireTargetAlive)
                    && targetDecoder != ActionTargetCodec.PLAYER_UUID) {
                throw new IllegalStateException(
                        "RoleActionSpec " + id + " uses distance/line-of-sight/target-alive but declares targetDecoder "
                                + targetDecoder + "; only PLAYER_UUID targets may use those checks");
            }
            return new RoleActionSpec(this);
        }
    }
}
