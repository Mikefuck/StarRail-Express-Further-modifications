package com.habitrain.core.api.role.v2.definition;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A v2 {@code REPLACE} operation: hides an existing target role and surfaces a
 * replacement in its place.
 *
 * <p>The replacement is a full {@link RoleDefinition} compiled by core. The
 * {@link ReplacementIdentity} strategy decides whether the replacement keeps the
 * target's canonical id ({@code KEEP_CANONICAL_ID}) or takes a new id while the
 * target becomes an alias ({@code NEW_ID_WITH_ALIAS}). Only one replacement may
 * own a given target in a snapshot.
 */
public final class RoleReplacement {

    private final RoleKey target;
    private final ReplacementIdentity identity;
    private final RoleDefinition replacement;
    private final String entryKey;

    private RoleReplacement(Builder b) {
        this.target = Objects.requireNonNull(b.target, "target");
        this.identity = Objects.requireNonNull(b.identity, "identity");
        this.replacement = Objects.requireNonNull(b.replacement, "replacement");
        this.entryKey = b.entryKey;
    }

    public static Builder builder(RoleKey target, RoleDefinition replacement) {
        return new Builder().target(target).replacement(replacement);
    }

    /** Convenience builder over a namespace/path target and a replacement definition. */
    public static Builder builder(String targetNamespace, String targetPath, RoleDefinition replacement) {
        return builder(RoleKey.of(targetNamespace, targetPath), replacement);
    }

    public RoleKey target() { return target; }
    public ReplacementIdentity identity() { return identity; }
    public RoleDefinition replacement() { return replacement; }
    public @Nullable String entryKey() { return entryKey; }

    public static final class Builder {
        private RoleKey target;
        private ReplacementIdentity identity = ReplacementIdentity.KEEP_CANONICAL_ID;
        private RoleDefinition replacement;
        private String entryKey;

        public Builder target(RoleKey target) { this.target = Objects.requireNonNull(target, "target"); return this; }
        public Builder identity(ReplacementIdentity identity) {
            this.identity = Objects.requireNonNull(identity, "identity");
            return this;
        }
        public Builder replacement(RoleDefinition replacement) {
            this.replacement = Objects.requireNonNull(replacement, "replacement");
            return this;
        }
        public Builder entryKey(String entryKey) { this.entryKey = entryKey; return this; }

        public RoleReplacement build() {
            if (target == null) throw new IllegalStateException("target required");
            if (replacement == null) throw new IllegalStateException("replacement required");
            return new RoleReplacement(this);
        }
    }
}
