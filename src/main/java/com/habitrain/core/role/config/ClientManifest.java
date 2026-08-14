package com.habitrain.core.role.config;

import java.util.Map;
import java.util.Objects;

/**
 * The client's local manifest used for the §14.2 handshake. Common (no client
 * classes): the physical client builds it from {@code FabricLoader} + the loaded
 * client-extension registry. Hash fields are nullable so the client trusts the
 * server when it cannot compute an independent fingerprint.
 *
 * @param coreApiVersion          the v2 API version this client understands
 * @param localProviderVersions   mod id → version for every provider-mod present
 * @param hasPresentationResources whether the client ships the server's
 *                                presentation metadata resources
 * @param expectedDefinitionHash  client-computed gameplay definition hash, or
 *                                {@code null} to trust the server
 * @param expectedPresentationHash client-computed presentation hash, or
 *                                {@code null} to trust the server
 */
public record ClientManifest(
        String coreApiVersion,
        Map<String, String> localProviderVersions,
        boolean hasPresentationResources,
        String expectedDefinitionHash,
        String expectedPresentationHash) {

    public ClientManifest {
        Objects.requireNonNull(coreApiVersion, "coreApiVersion");
        localProviderVersions = localProviderVersions == null
                ? Map.of() : Map.copyOf(localProviderVersions);
    }

    /** Convenience constructor that trusts the server for both hashes. */
    public ClientManifest(String coreApiVersion, Map<String, String> localProviderVersions,
                          boolean hasPresentationResources) {
        this(coreApiVersion, localProviderVersions, hasPresentationResources, null, null);
    }
}
