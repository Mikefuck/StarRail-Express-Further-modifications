package com.habitrain.core.role.config;

/**
 * One provider row in the connection manifest (fix-doc §14.2): the mod id, its
 * version, and whether a matching client-side declaration is required to play.
 */
public record RoleProviderManifest(String providerId, String version, boolean requiredClient) {
}
