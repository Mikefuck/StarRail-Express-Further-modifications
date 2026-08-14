package com.habitrain.core.api.role.v2.definition;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * A v2 {@code ALIAS} registration: maps an old / legacy role id to a canonical
 * role id for id compatibility and data migration. It never changes behavior —
 * it only redirects lookups, stored ids and command resolution.
 *
 * <p>Both ends are canonical {@link RoleKey}s. {@code from} is the legacy id
 * being migrated away from; {@code to} is the canonical id it should resolve to.
 * Alias rings and dangling targets are detected at freeze time.
 */
public record RoleAlias(RoleKey from, RoleKey to) {

    public RoleAlias {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) {
            throw new IllegalArgumentException("Alias from and to must differ: " + from);
        }
    }

    /** Builds an alias from a namespace/path pair to another namespace/path pair. */
    public static RoleAlias of(String fromNamespace, String fromPath,
                                String toNamespace, String toPath) {
        return new RoleAlias(RoleKey.of(fromNamespace, fromPath),
                RoleKey.of(toNamespace, toPath));
    }

    /** Builds an alias from two already-normalized locations. */
    public static RoleAlias of(ResourceLocation from, ResourceLocation to) {
        return new RoleAlias(RoleKey.of(from), RoleKey.of(to));
    }

    /** Builds an alias from two canonical keys. */
    public static RoleAlias of(RoleKey from, RoleKey to) {
        return new RoleAlias(from, to);
    }
}
