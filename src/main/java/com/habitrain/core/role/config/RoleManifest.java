package com.habitrain.core.role.config;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * The server's role-extension manifest sent at connect and after every config
 * change (fix-doc §14.2). Pure data — never references client classes, so it is
 * safe on the dedicated-server init chain and unit-testable without a game.
 *
 * <p>Capabilities are advertised at granular level (audit P1-2): HUD /
 * instinct / skin / name-render / screen are separate entries instead of the
 * coarse {@code client_ext}, and {@link #experimentalCapabilities()} lists the
 * ones that are declared but not yet backed by a runtime consumer, so a
 * provider never mistakes a stored rule for a delivered feature.
 *
 * @param coreApiVersion      the v2 API version string
 * @param providers           every role-extension provider with its version
 * @param capabilities        the capability set the server supports
 * @param definitionHash      SHA-256 over the compiled entry view (gameplay)
 * @param lobbySnapshotId     the current lobby snapshot id (opaque identity)
 * @param roundSnapshotId     the in-progress round snapshot id, or {@code null}
 * @param presentationHash    SHA-256 over effective role presentation metadata
 * @param configJson          the serialized {@code roleExtensionsV2} section
 * @param experimentalCapabilities capabilities that are stored but not yet
 *                                consumed at runtime (audit P1-2)
 */
public record RoleManifest(
        String coreApiVersion,
        List<RoleProviderManifest> providers,
        Set<String> capabilities,
        String definitionHash,
        String lobbySnapshotId,
        @Nullable String roundSnapshotId,
        String presentationHash,
        String configJson,
        Set<String> experimentalCapabilities) {

    public RoleManifest {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        experimentalCapabilities = experimentalCapabilities == null
                ? Set.of() : Set.copyOf(experimentalCapabilities);
    }

    public RoleManifest(String coreApiVersion, List<RoleProviderManifest> providers,
                        Set<String> capabilities, String definitionHash, String lobbySnapshotId,
                        @Nullable String roundSnapshotId, String presentationHash, String configJson) {
        this(coreApiVersion, providers, capabilities, definitionHash, lobbySnapshotId,
                roundSnapshotId, presentationHash, configJson, Set.of());
    }
}
