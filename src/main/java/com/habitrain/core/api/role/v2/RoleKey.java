package com.habitrain.core.api.role.v2;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable canonical identity of a role in the v2 role catalog.
 *
 * <p>A {@code RoleKey} is the canonical role identity that survives
 * alias/replacement resolution; the raw upstream object id is a plain
 * {@link ResourceLocation}. The namespace and path are lower-cased and
 * normalized exactly like {@code RoleOverrideApi#roleId}, so lookups are
 * stable regardless of the case the caller supplies.
 */
public record RoleKey(ResourceLocation location) {

    public RoleKey {
        Objects.requireNonNull(location, "location");
    }

    /** Builds a key from a namespace and path, normalizing both to lower case. */
    public static RoleKey of(String namespace, String path) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        String ns = namespace.trim().toLowerCase(Locale.ROOT);
        String p = path.trim().toLowerCase(Locale.ROOT);
        if (ns.isEmpty() || p.isEmpty()) {
            throw new IllegalArgumentException("namespace and path must not be blank");
        }
        return new RoleKey(ResourceLocation.fromNamespaceAndPath(ns, p));
    }

    /** Wraps an existing location as a canonical key. */
    public static RoleKey of(ResourceLocation location) {
        return new RoleKey(location);
    }

    /**
     * Parses the {@code namespace:path} string form.
     *
     * @return the parsed key, or {@code null} if the value is null/invalid
     */
    public static RoleKey tryParse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(value);
        return location == null ? null : new RoleKey(location);
    }

    public String namespace() {
        return location.getNamespace();
    }

    public String path() {
        return location.getPath();
    }

    @Override
    public String toString() {
        return location.toString();
    }
}
