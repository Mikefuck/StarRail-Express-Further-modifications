package com.habitrain.core.api.role.v2.definition;

import com.habitrain.core.api.role.book.RoleBookContent;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import io.wifi.starrailexpress.api.SRERole;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Immutable declarative definition of a role to be added through the v2
 * {@code ADD} model.
 *
 * <p>A {@code RoleDefinition} is the complete static description of a new role:
 * canonical id, presentation (color, mood type; name/description via
 * translation keys), faction, spawn, compatibility/integration flags, and
 * optional initial items and shop. It is compiled by core into an upstream
 * {@link io.wifi.starrailexpress.api.SRERole} (see {@code ManagedSRERole}) and
 * registered exactly once.
 */
public final class RoleDefinition {

    /**
     * Factory hook for {@link RoleDefinition}. When present, core invokes it
     * exactly once during staging to obtain a fully configured custom
     * {@link SRERole} subclass (e.g. a {@code CustomWinnerRole}). The returned
     * role must carry the definition's canonical id; core still validates,
     * catalogs, snapshots and gates it like any other v2 ADD role.
     */
    @FunctionalInterface
    public interface RoleFactory {
        SRERole create(RoleDefinition definition);
    }

    private final RoleKey key;
    private final RolePresentation presentation;
    private final RoleFactionProfile faction;
    private final RoleSpawnProfile spawn;
    private final RoleCompatibilityProfile compatibility;
    private final @Nullable RoleInventoryProfile inventory;
    private final @Nullable RoleEconomyProfile economy;
    private final @Nullable RoleVisibilityProfile visibility;
    private final @Nullable RoleRelationProfile relations;
    private final List<RoleSkillSpec> skills;
    private final @Nullable RoleBookContent book;
    private final @Nullable RoleFactory roleFactory;
    private final int maxSprintTime;
    private final boolean canSeeTime;

    private RoleDefinition(Builder b) {
        this.key = Objects.requireNonNull(b.key, "key");
        this.presentation = Objects.requireNonNull(b.presentation, "presentation");
        this.faction = Objects.requireNonNull(b.faction, "faction");
        this.spawn = Objects.requireNonNull(b.spawn, "spawn");
        this.compatibility = Objects.requireNonNull(b.compatibility, "compatibility");
        this.inventory = b.inventory;
        this.economy = b.economy;
        this.visibility = b.visibility;
        this.relations = b.relations;
        this.skills = List.copyOf(b.skills);
        this.book = b.book;
        this.roleFactory = b.roleFactory;
        this.maxSprintTime = b.maxSprintTime;
        this.canSeeTime = b.canSeeTime;
    }

    public static Builder builder(RoleKey key) {
        return new Builder().key(key);
    }

    /** Convenience builder over a namespace/path pair (normalized by {@link RoleKey}). */
    public static Builder builder(String namespace, String path) {
        return builder(RoleKey.of(namespace, path));
    }

    /** Convenience builder over an already-normalized {@link ResourceLocation}. */
    public static Builder builder(net.minecraft.resources.ResourceLocation location) {
        return builder(RoleKey.of(location));
    }

    public RoleKey key() { return key; }
    public RolePresentation presentation() { return presentation; }
    public RoleFactionProfile faction() { return faction; }
    public RoleSpawnProfile spawn() { return spawn; }
    public RoleCompatibilityProfile compatibility() { return compatibility; }
    public @Nullable RoleInventoryProfile inventory() { return inventory; }
    public @Nullable RoleEconomyProfile economy() { return economy; }
    public @Nullable RoleVisibilityProfile visibility() { return visibility; }
    public @Nullable RoleRelationProfile relations() { return relations; }
    public List<RoleSkillSpec> skills() { return skills; }
    public @Nullable RoleBookContent book() { return book; }
    public @Nullable RoleFactory roleFactory() { return roleFactory; }
    public int maxSprintTime() { return maxSprintTime; }
    public boolean canSeeTime() { return canSeeTime; }

    public static final class Builder {
        private RoleKey key;
        private RolePresentation presentation;
        private RoleFactionProfile faction;
        private RoleSpawnProfile spawn;
        private RoleCompatibilityProfile compatibility;
        private @Nullable RoleInventoryProfile inventory;
        private @Nullable RoleEconomyProfile economy;
        private @Nullable RoleVisibilityProfile visibility;
        private @Nullable RoleRelationProfile relations;
        private final java.util.ArrayList<RoleSkillSpec> skills = new java.util.ArrayList<>();
        private @Nullable RoleBookContent book;
        private @Nullable RoleFactory roleFactory;
        private int maxSprintTime = -1;
        private boolean canSeeTime;

        public Builder key(RoleKey key) { this.key = Objects.requireNonNull(key, "key"); return this; }

        public Builder presentation(RolePresentation presentation) {
            this.presentation = Objects.requireNonNull(presentation, "presentation");
            return this;
        }

        public Builder faction(RoleFactionProfile faction) {
            this.faction = Objects.requireNonNull(faction, "faction");
            return this;
        }

        public Builder spawn(RoleSpawnProfile spawn) {
            this.spawn = Objects.requireNonNull(spawn, "spawn");
            return this;
        }

        public Builder compatibility(RoleCompatibilityProfile compatibility) {
            this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
            return this;
        }

        public Builder inventory(@Nullable RoleInventoryProfile inventory) {
            this.inventory = inventory;
            return this;
        }

        public Builder economy(@Nullable RoleEconomyProfile economy) {
            this.economy = economy;
            return this;
        }

        public Builder visibility(@Nullable RoleVisibilityProfile visibility) {
            this.visibility = visibility;
            return this;
        }

        public Builder relations(@Nullable RoleRelationProfile relations) {
            this.relations = relations;
            return this;
        }

        public Builder skill(RoleSkillSpec skill) {
            this.skills.add(Objects.requireNonNull(skill, "skill"));
            return this;
        }

        public Builder skills(RoleSkillSpec... skills) {
            for (RoleSkillSpec skill : skills) {
                skill(skill);
            }
            return this;
        }

        public Builder book(@Nullable RoleBookContent book) {
            this.book = book;
            return this;
        }

        public Builder roleFactory(RoleFactory roleFactory) {
            this.roleFactory = Objects.requireNonNull(roleFactory, "roleFactory");
            return this;
        }

        public Builder maxSprintTime(int maxSprintTime) {
            this.maxSprintTime = maxSprintTime;
            return this;
        }

        public Builder canSeeTime(boolean canSeeTime) {
            this.canSeeTime = canSeeTime;
            return this;
        }

        public RoleDefinition build() {
            if (key == null) throw new IllegalStateException("key required");
            if (presentation == null) throw new IllegalStateException("presentation required");
            if (faction == null) throw new IllegalStateException("faction required");
            if (spawn == null) throw new IllegalStateException("spawn required");
            if (compatibility == null) throw new IllegalStateException("compatibility required");
            if (maxSprintTime < 0) throw new IllegalStateException("maxSprintTime required (>= 0)");
            return new RoleDefinition(this);
        }
    }
}
