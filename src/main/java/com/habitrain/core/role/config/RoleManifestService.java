package com.habitrain.core.role.config;

import com.habitrain.core.api.role.v2.RoleExtensionApi;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.role.client.RoleClientExtensionRegistry;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the server's role-extension manifest (fix-doc §14.2). Called at join and
 * re-broadcast after every {@code roleExtensionsV2} config change so clients can
 * compare hashes and provider versions. The heavy lifting is pure
 * ({@link RoleManifestHashes}, {@link RoleManifest}); this class only gathers
 * live state from the registry, the snapshot manager and the config service.
 */
public final class RoleManifestService {

    private RoleManifestService() {}

    /**
     * The capability set the v2 platform fully supports in this release
     * (audit P1-2: granular client capabilities replace the coarse
     * {@code client_ext}, so a provider can tell what is actually consumed).
     */
    public static final Set<String> CAPABILITIES = Set.of(
            "add", "modify", "replace", "alias", "state", "action", "hooks",
            "client_hud", "client_instinct", "client_skin");

    /**
     * Capabilities that are registered and diagnosable but NOT yet backed by a
     * runtime consumer (audit P1-2: name-render rules are only stored; screen
     * specs are only stored). Advertised separately so no provider mistakes a
     * stored declaration for a delivered feature.
     */
    public static final Set<String> EXPERIMENTAL_CAPABILITIES = Set.of(
            "client_name_render", "client_screen");

    public static RoleManifest build() {
        String coreApi = RoleExtensionApi.instance().apiVersion();
        List<RoleProviderManifest> providers = providers();
        RoleSnapshot snapshot = RoleSnapshotManager.INSTANCE.current();
        String lobby = snapshot == null ? "none" : snapshot.id().toString();
        RoleSnapshot round = RoleSnapshotManager.INSTANCE.round();
        String roundId = round == null ? null : round.id().toString();
        String definitionHash = RoleManifestHashes.definitionHash(
                RoleExtensionRegistry.INSTANCE.getCompiledEntries());
        String presentationHash = RoleManifestHashes.presentationHash(snapshot);
        String configJson = RoleExtensionConfigService.INSTANCE.toJsonString();
        return new RoleManifest(coreApi, providers, CAPABILITIES,
                definitionHash, lobby, roundId, presentationHash, configJson,
                EXPERIMENTAL_CAPABILITIES);
    }

    private static List<RoleProviderManifest> providers() {
        Set<String> ids = new LinkedHashSet<>(RoleExtensionRegistry.INSTANCE.getProviders());
        List<RoleProviderManifest> out = new ArrayList<>();
        for (String providerId : ids) {
            out.add(new RoleProviderManifest(providerId, versionOf(providerId),
                    isClientExtensionProvider(providerId)));
        }
        return out;
    }

    private static String versionOf(String providerId) {
        try {
            var container = FabricLoader.getInstance().getModContainer(providerId);
            return container.map(mc -> mc.getMetadata().getVersion().getFriendlyString()).orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    /** A provider is required on the client when the same mod also declares client extensions. */
    private static boolean isClientExtensionProvider(String providerId) {
        try {
            for (var container : FabricLoader.getInstance().getEntrypointContainers(
                    RoleClientExtensionRegistry.ENTRYPOINT_KEY, Object.class)) {
                if (container.getProvider().getMetadata().getId().equals(providerId)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // entrypoint container introspection is best-effort
        }
        return false;
    }
}