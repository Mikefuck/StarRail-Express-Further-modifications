package com.habitrain.core.role.config;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * The server's role-extension manifest sent at connect and after every config
 * change (fix-doc §14.2). Pure data — never references client classes, so it is
 * safe on the dedicated-server init chain and unit-testable without a game.
 *
 * @param coreApiVersion      the v2 API version string
 * @param providers           every role-extension provider with its version
 * @param capabilities        the capability set the server supports
 * @param definitionHash      SHA-256 over the compiled entry view (gameplay)
 * @param lobbySnapshotId     the current lobby snapshot id (opaque identity)
 * @param roundSnapshotId     the in-progress round snapshot id, or {@code null}
 * @param presentationHash    SHA-256 over effective role presentation metadata
 * @param configJson          the serialized {@code roleExtensionsV2} section
 */
public record RoleManifest(
        String coreApiVersion,
        List<RoleProviderManifest> providers,
        Set<String> capabilities,
        String definitionHash,
        String lobbySnapshotId,
        @Nullable String roundSnapshotId,
        String presentationHash,
        String configJson) {
}
