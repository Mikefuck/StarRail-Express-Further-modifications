package com.habitrain.core.api.role.v2.capability;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Namespaced capability identity (design §16.4 / §18.4).
 *
 * <p>Built-in keys live under {@code habitrain_core}. Providers may declare
 * extra keys; {@link RoleCapabilityApi#supports} answers whether an adapter
 * is bound this process.
 */
public record RoleCapabilityKey(ResourceLocation id) {

    public static final RoleCapabilityKey VOICE = of("habitrain_core", "voice");
    public static final RoleCapabilityKey CHAT = of("habitrain_core", "chat");

    public RoleCapabilityKey {
        Objects.requireNonNull(id, "id");
    }

    public static RoleCapabilityKey of(String namespace, String path) {
        return new RoleCapabilityKey(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public static RoleCapabilityKey of(ResourceLocation id) {
        return new RoleCapabilityKey(id);
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
