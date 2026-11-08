package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.RoleExtensionApi;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.role.config.ClientManifest;
import com.habitrain.core.role.config.RoleHandshakeMatcher;
import com.habitrain.core.role.config.RoleHandshakeResult;
import com.habitrain.core.role.config.RoleHandshakeStatus;
import com.habitrain.core.role.config.RoleManifest;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side handshake state (fix-doc §14.2): stores the last server manifest
 * and the computed {@link RoleHandshakeResult}. The physical client builds its
 * local manifest from {@code FabricLoader} (every loaded mod + whether it ships
 * the role client-extension entrypoint) and the common matcher decides
 * OK / DEGRADED / REJECTED / HASH_MISMATCH. The role-extension Mod Menu page and
 * any connection gate read {@link #status()}/{@link #message()} here.
 */
@Environment(EnvType.CLIENT)
public final class RoleHandshakeState {

    public static final RoleHandshakeState INSTANCE = new RoleHandshakeState();

    private volatile @Nullable RoleManifest serverManifest;
    private volatile RoleHandshakeResult result =
            new RoleHandshakeResult(RoleHandshakeStatus.OK, null, java.util.List.of());

    private RoleHandshakeState() {}

    /** Records a received server manifest and recomputes the handshake result. */
    public void accept(RoleManifest manifest) {
        accept(manifest, buildLocalManifest());
    }

    void accept(RoleManifest manifest, ClientManifest localManifest) {
        this.serverManifest = manifest;
        this.result = manifest == null ? new RoleHandshakeResult(
                RoleHandshakeStatus.HASH_MISMATCH, "空 manifest", java.util.List.of())
                : RoleHandshakeMatcher.match(manifest, localManifest);
    }

    /** Re-evaluates an already received manifest after the snapshot hash changes. */
    public void onSnapshotUpdated() {
        onSnapshotUpdated(buildLocalManifest());
    }

    void onSnapshotUpdated(ClientManifest localManifest) {
        RoleManifest manifest = this.serverManifest;
        if (manifest != null) {
            this.result = RoleHandshakeMatcher.match(manifest, localManifest);
        }
    }

    /**
     * Builds the client's local manifest from the loaded mod set and local role registry.
     */
    public static ClientManifest buildLocalManifest() {
        Map<String, String> versions = new LinkedHashMap<>();
        try {
            for (var container : FabricLoader.getInstance().getAllMods()) {
                String id = container.getMetadata().getId();
                String version = container.getMetadata().getVersion().getFriendlyString();
                versions.put(id, version);
            }
        } catch (Throwable t) {
            // best-effort: an empty map makes every required provider "missing"
        }
        boolean hasPresentationResources = !versions.isEmpty()
                && RoleClientExtensionHooks.isLoaded();
        String api = RoleExtensionApi.instance().apiVersion();
        java.util.Set<String> loadedClientExtensions = RoleClientExtensionApi.instance()
                .loadedProviderIds();
        String localDefinitionHash = computeLocalDefinitionHash();
        return new ClientManifest(api, versions, hasPresentationResources,
                localDefinitionHash, null, loadedClientExtensions);
    }

    private static @Nullable String computeLocalDefinitionHash() {
        try {
            return com.habitrain.core.role.config.RoleManifestHashes.definitionHash();
        } catch (RuntimeException failure) {
            com.habitrain.core.HabiTrainCore.LOGGER.warn("Cannot compute local role declaration hash", failure);
            return null;
        }
    }

    public @Nullable RoleManifest serverManifest() {
        return serverManifest;
    }

    public RoleHandshakeResult result() {
        return result;
    }

    public RoleHandshakeStatus status() {
        return result.status();
    }

    public @Nullable String message() {
        return result.message();
    }

    public boolean isOk() {
        return result.status() == RoleHandshakeStatus.OK;
    }

    /** Clears handshake state when leaving a server. */
    public void reset() {
        this.serverManifest = null;
        this.result = new RoleHandshakeResult(RoleHandshakeStatus.OK, null, java.util.List.of());
    }
}
