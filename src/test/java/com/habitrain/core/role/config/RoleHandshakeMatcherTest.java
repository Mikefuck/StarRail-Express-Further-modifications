package com.habitrain.core.role.config;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleOperation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase F §14.2: the pure manifest handshake matrix and the deterministic hashes.
 */
class RoleHandshakeMatcherTest {

    private static final String API = "2.0";

    private static RoleManifest manifest(List<RoleProviderManifest> providers, String defHash,
                                         String presHash) {
        return new RoleManifest(API, providers, java.util.Set.of("add", "state"),
                defHash, "role-snapshot-v1", null, presHash, "{}");
    }

    private static RoleProviderManifest required(String id, String version) {
        return new RoleProviderManifest(id, version, true);
    }

    private static RoleProviderManifest optional(String id, String version) {
        return new RoleProviderManifest(id, version, false);
    }

    @Test
    void identicalManifestsAreOk() {
        RoleManifest server = manifest(List.of(required("habitrain_dlc", "1.2.0")), "hashA", "presA");
        ClientManifest local = new ClientManifest(API, Map.of("habitrain_dlc", "1.2.0"), true,
                null, null, Set.of("habitrain_dlc"));
        RoleHandshakeResult result = RoleHandshakeMatcher.match(server, local);
        assertEquals(RoleHandshakeStatus.OK, result.status());
    }

    @Test
    void requiredProviderWithoutLoadedClientExtensionsIsRejected() {
        // Audit P1-4: the mod is present and versioned, but its client
        // extensions never loaded (or the entrypoint is missing) -> fail closed.
        RoleManifest server = manifest(List.of(required("habitrain_dlc", "1.2.0")), "hashA", "presA");
        ClientManifest local = new ClientManifest(API, Map.of("habitrain_dlc", "1.2.0"), true);
        RoleHandshakeResult result = RoleHandshakeMatcher.match(server, local);
        assertEquals(RoleHandshakeStatus.REJECTED_MISSING_PROVIDER, result.status());
        assertTrue(result.message().contains("客户端扩展未加载"));
    }

    @Test
    void missingRequiredProviderIsRejectedWithModuleList() {
        RoleManifest server = manifest(List.of(
                required("habitrain_dlc", "1.2.0"),
                required("moretrainjobs", "0.5.0")), "hashA", "presA");
        ClientManifest local = new ClientManifest(API, Map.of("habitrain_dlc", "1.2.0"), true,
                null, null, Set.of("habitrain_dlc"));
        RoleHandshakeResult result = RoleHandshakeMatcher.match(server, local);
        assertEquals(RoleHandshakeStatus.REJECTED_MISSING_PROVIDER, result.status());
        assertTrue(result.missingModules().stream().anyMatch(m -> m.contains("moretrainjobs")));
        assertTrue(result.message().contains("moretrainjobs"));
    }

    @Test
    void versionMismatchIsRejected() {
        RoleManifest server = manifest(List.of(required("habitrain_dlc", "1.2.0")), "hashA", "presA");
        ClientManifest local = new ClientManifest(API, Map.of("habitrain_dlc", "1.1.0"), true);
        RoleHandshakeResult result = RoleHandshakeMatcher.match(server, local);
        assertEquals(RoleHandshakeStatus.REJECTED_MISSING_PROVIDER, result.status());
        assertTrue(result.message().contains("1.1.0"));
    }

    @Test
    void optionalProviderMissingDoesNotReject() {
        RoleManifest server = manifest(List.of(optional("presentation_mod", "1.0.0")), "hashA", "presA");
        ClientManifest local = new ClientManifest(API, Map.of(), true);
        assertEquals(RoleHandshakeStatus.OK, RoleHandshakeMatcher.match(server, local).status());
    }

    @Test
    void apiVersionMajorMinorMismatchIsRejected() {
        RoleManifest server = manifest(List.of(), "hashA", "presA");
        ClientManifest local = new ClientManifest("1.9", Map.of(), true);
        assertEquals(RoleHandshakeStatus.REJECTED_MISSING_PROVIDER,
                RoleHandshakeMatcher.match(server, local).status());
    }

    @Test
    void apiVersionPatchDriftIsAccepted() {
        RoleManifest server = manifest(List.of(), "hashA", "presA");
        ClientManifest local = new ClientManifest("2.0.3", Map.of(), true);
        assertEquals(RoleHandshakeStatus.OK, RoleHandshakeMatcher.match(server, local).status());
    }

    @Test
    void definitionHashMismatchIsHashMismatch() {
        RoleManifest server = manifest(List.of(), "serverHash", "presA");
        ClientManifest local = new ClientManifest(API, Map.of(), true, "clientHash", null);
        RoleHandshakeResult result = RoleHandshakeMatcher.match(server, local);
        assertEquals(RoleHandshakeStatus.HASH_MISMATCH, result.status());
        assertTrue(result.message().contains("哈希"));
    }

    @Test
    void trustServerWhenClientHasNoLocalHash() {
        RoleManifest server = manifest(List.of(), "serverHash", "presA");
        ClientManifest local = new ClientManifest(API, Map.of(), true, null, null);
        assertEquals(RoleHandshakeStatus.OK, RoleHandshakeMatcher.match(server, local).status());
    }

    @Test
    void presentationMismatchWithoutResourcesDegrades() {
        RoleManifest server = manifest(List.of(), "hashA", "serverPres");
        ClientManifest local = new ClientManifest(API, Map.of(), false, null, "clientPres");
        RoleHandshakeResult result = RoleHandshakeMatcher.match(server, local);
        assertEquals(RoleHandshakeStatus.DEGRADED_CLIENT_EXTENSION, result.status());
    }

    @Test
    void presentationMismatchWithResourcesIsOk() {
        RoleManifest server = manifest(List.of(), "hashA", "serverPres");
        ClientManifest local = new ClientManifest(API, Map.of(), true, null, "clientPres");
        assertEquals(RoleHandshakeStatus.OK, RoleHandshakeMatcher.match(server, local).status());
    }

    // ------------------------------------------------------------------
    // hashes
    // ------------------------------------------------------------------

    private static ManagedRoleEntry<?> entry(String entryId, EntryStatus status) {
        return new ManagedRoleEntry<>(entryId, "example", "buff",
                RoleOperation.MODIFY, RoleKey.of("sre", "vigilante"), PatchPriority.NORMAL,
                RolePatch.builder(RoleKey.of("sre", "vigilante")).defaultMax(RolePatch.IntPatch.set(2)).build(),
                status, "test", false);
    }

    @Test
    void definitionHashIsStableAndOrderIndependent() {
        String a = RoleManifestHashes.definitionHash(List.of(entry("x$1", EntryStatus.ACTIVE)));
        String b = RoleManifestHashes.definitionHash(List.of(entry("x$1", EntryStatus.ACTIVE)));
        assertEquals(a, b);
        String reordered = RoleManifestHashes.definitionHash(List.of(
                entry("z$2", EntryStatus.DISABLED), entry("a$1", EntryStatus.ACTIVE)));
        String sorted = RoleManifestHashes.definitionHash(List.of(
                entry("a$1", EntryStatus.ACTIVE), entry("z$2", EntryStatus.DISABLED)));
        assertEquals(sorted, reordered);
        String different = RoleManifestHashes.definitionHash(List.of(entry("x$1", EntryStatus.DISABLED)));
        assertNotEquals(a, different);
    }

    @Test
    void presentationHashIsStable() {
        // No snapshot -> hash of the empty set.
        String a = RoleManifestHashes.presentationHash(null);
        String b = RoleManifestHashes.presentationHash(null);
        assertEquals(a, b);
        assertFalse(a.isBlank());
    }
}
