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
     * {@code client_hud} is stable only for the TEXT/BADGE kinds the stock
     * client actually renders.
     */
    public static final Set<String> CAPABILITIES = Set.of(
            "add", "modify", "replace", "alias", "state", "action", "hooks",
            "client_hud", "client_instinct", "client_skin");

    /**
     * Capabilities that are registered and diagnosable but NOT yet backed by a
     * runtime consumer (audit P1-2/P1-5): name-render rules and screen specs
     * are only stored; the ICON/PROGRESS/COOLDOWN/CHARGE HUD kinds
     * ({@code client_hud_visual}) have no stock visual model yet. Advertised
     * separately so no provider mistakes a stored declaration for a delivered
     * feature.
     */
    public static final Set<String> EXPERIMENTAL_CAPABILITIES = Set.of(
            "client_name_render", "client_screen", "client_hud_visual");

    public static RoleManifest build() {
        String coreApi = RoleExtensionApi.instance().apiVersion();
        List<RoleProviderManifest> providers = providers();
        RoleSnapshot snapshot = RoleSnapshotManager.INSTANCE.current();
        String lobby = snapshot == null ? "none" : snapshot.id().toString();
        RoleSnapshot round = RoleSnapshotManager.INSTANCE.round();
        String roundId = round == null ? null : round.id().toString();
        String definitionHash = RoleManifestHashes.definitionHash();
        String presentationHash = RoleManifestHashes.presentationHash(snapshot);
        String configJson = RoleExtensionConfigService.INSTANCE.toJsonString();
        return new RoleManifest(coreApi, providers, CAPABILITIES,
                definitionHash, lobby, roundId, presentationHash, configJson,
                EXPERIMENTAL_CAPABILITIES);
    }

    /**
     * The authoritative provider list (audit P1-4): every provider whose
     * registration transaction committed, whatever declaration types it used,
     * with {@code requiredClient} taken from the provider's own explicit
     * declaration — never guessed from entrypoint presence.
     */
    private static List<RoleProviderManifest> providers() {
        Set<String> ids = new LinkedHashSet<>(RoleExtensionRegistry.INSTANCE.providerIds());
        Set<String> required = RoleExtensionRegistry.INSTANCE.requiredClientProviderIds();
        List<RoleProviderManifest> out = new ArrayList<>();
        for (String providerId : ids) {
            out.add(new RoleProviderManifest(providerId, versionOf(providerId),
                    required.contains(providerId)));
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
}