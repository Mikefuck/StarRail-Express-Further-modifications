package com.habitrain.core.api.role.v2.behavior;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable container of the behavior hooks a provider attaches to one role.
 *
 * <p>Each category is optional; a provider implements only the interfaces it
 * needs. Built via {@link #builder()}.
 */
public final class RoleHooks {

    private final @Nullable RoleLifecycleHooks lifecycle;
    private final @Nullable RoleCombatHooks combat;
    private final @Nullable RoleTickHooks tick;
    private final @Nullable RoleInteractionHooks interaction;
    private final @Nullable RoleShopHooks shop;
    private final @Nullable RoleTaskHooks task;
    private final @Nullable RoleMeetingHooks meeting;
    private final @Nullable RoleWinHooks win;

    private RoleHooks(Builder b) {
        this.lifecycle = b.lifecycle;
        this.combat = b.combat;
        this.tick = b.tick;
        this.interaction = b.interaction;
        this.shop = b.shop;
        this.task = b.task;
        this.meeting = b.meeting;
        this.win = b.win;
    }

    public static Builder builder() {
        return new Builder();
    }

    public @Nullable RoleLifecycleHooks lifecycle() { return lifecycle; }
    public @Nullable RoleCombatHooks combat() { return combat; }
    public @Nullable RoleTickHooks tick() { return tick; }
    public @Nullable RoleInteractionHooks interaction() { return interaction; }
    public @Nullable RoleShopHooks shop() { return shop; }
    public @Nullable RoleTaskHooks task() { return task; }
    public @Nullable RoleMeetingHooks meeting() { return meeting; }
    public @Nullable RoleWinHooks win() { return win; }

    /** Whether this container carries at least one hook category. */
    public boolean isEmpty() {
        return lifecycle == null && combat == null && tick == null && interaction == null
                && shop == null && task == null && meeting == null && win == null;
    }

    public static final class Builder {
        private RoleLifecycleHooks lifecycle;
        private RoleCombatHooks combat;
        private RoleTickHooks tick;
        private RoleInteractionHooks interaction;
        private RoleShopHooks shop;
        private RoleTaskHooks task;
        private RoleMeetingHooks meeting;
        private RoleWinHooks win;

        public Builder lifecycle(RoleLifecycleHooks hooks) {
            this.lifecycle = Objects.requireNonNull(hooks, "lifecycle");
            return this;
        }

        public Builder combat(RoleCombatHooks hooks) {
            this.combat = Objects.requireNonNull(hooks, "combat");
            return this;
        }

        public Builder tick(RoleTickHooks hooks) {
            this.tick = Objects.requireNonNull(hooks, "tick");
            return this;
        }

        public Builder interaction(RoleInteractionHooks hooks) {
            this.interaction = Objects.requireNonNull(hooks, "interaction");
            return this;
        }

        public Builder shop(RoleShopHooks hooks) {
            this.shop = Objects.requireNonNull(hooks, "shop");
            return this;
        }

        public Builder task(RoleTaskHooks hooks) {
            this.task = Objects.requireNonNull(hooks, "task");
            return this;
        }

        public Builder meeting(RoleMeetingHooks hooks) {
            this.meeting = Objects.requireNonNull(hooks, "meeting");
            return this;
        }

        public Builder win(RoleWinHooks hooks) {
            this.win = Objects.requireNonNull(hooks, "win");
            return this;
        }

        public RoleHooks build() {
            RoleHooks hooks = new RoleHooks(this);
            if (hooks.isEmpty()) {
                throw new IllegalStateException("RoleHooks must carry at least one hook category");
            }
            return hooks;
        }
    }
}
