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

    /**
     * Wraps an existing location as a canonical key.
     *
     * <p><b>审核 R-13</b>：本重载<b>不</b>做 trim/lowercase，要求传入的
     * {@link ResourceLocation} 已经是规范形式（上游角色 ID 就是规范形式）。
     * 需要规范化大小写/空白时请用 {@link #of(String, String)} 或 {@link #tryParse(String)}。</p>
     */
    public static RoleKey of(ResourceLocation location) {
        return new RoleKey(location);
    }

    /**
     * Parses the {@code namespace:path} string form.
     *
     * <p><b>审核 R-13</b>：旧实现直接 {@code ResourceLocation.tryParse(原始串)}，而
     * 1.21 的 {@link ResourceLocation} 是严格校验且<b>不会</b>自动小写——同一个
     * {@code "Example_Mod:Shadow_Killer"} 走 {@link #of(String, String)} 会成功并规范化，
     * 走本方法却返回 {@code null}。现在这里先 trim + lowercase，与 {@code of} 保持一致。</p>
     *
     * @return the parsed key, or {@code null} if the value is null/invalid
     */
    public static RoleKey tryParse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(value.trim().toLowerCase(Locale.ROOT));
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
