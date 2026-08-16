package com.habitrain.core.role.config;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The client's local manifest used for the §14.2 handshake. Common (no client
 * classes): the physical client builds it from {@code FabricLoader} + the loaded
 * client-extension registry. Hash fields are nullable so the client trusts the
 * server when it cannot compute an independent fingerprint.
 *
 * @param coreApiVersion           the v2 API version this client understands
 * @param localProviderVersions    mod id → version for every provider-mod present
 * @param hasPresentationResources whether the client ships the server's
 *                                 presentation metadata resources
 * @param expectedDefinitionHash   client-computed gameplay definition hash, or
 *                                 {@code null} to trust the server
 * @param expectedPresentationHash client-computed presentation hash, or
 *                                 {@code null} to trust the server
 * @param localClientExtensionProviders provider mod ids whose client-extension
 *                                 registration committed on this client
 *                                 (audit P1-4); required providers must be in
 *                                 this set as well as the version map
 */
public record ClientManifest(
        String coreApiVersion,
        Map<String, String> localProviderVersions,
        boolean hasPresentationResources,
        String expectedDefinitionHash,
        String expectedPresentationHash,
        Set<String> localClientExtensionProviders) {

    public ClientManifest {
        Objects.requireNonNull(coreApiVersion, "coreApiVersion");
        localProviderVersions = localProviderVersions == null
                ? Map.of() : Map.copyOf(localProviderVersions);
        localClientExtensionProviders = localClientExtensionProviders == null
                ? Set.of() : Set.copyOf(localClientExtensionProviders);
    }

    /** Convenience constructor that trusts the server for both hashes. */
    public ClientManifest(String coreApiVersion, Map<String, String> localProviderVersions,
                          boolean hasPresentationResources) {
        this(coreApiVersion, localProviderVersions, hasPresentationResources, null, null, Set.of());
    }

    /** Convenience constructor for handshake tests with explicit hashes. */
    public ClientManifest(String coreApiVersion, Map<String, String> localProviderVersions,
                          boolean hasPresentationResources,
                          String expectedDefinitionHash, String expectedPresentationHash) {
        this(coreApiVersion, localProviderVersions, hasPresentationResources,
                expectedDefinitionHash, expectedPresentationHash, Set.of());
    }
}
