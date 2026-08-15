package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.RoleExtensionApi;
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
        this.serverManifest = manifest;
        this.result = manifest == null ? new RoleHandshakeResult(
                RoleHandshakeStatus.HASH_MISMATCH, "空 manifest", java.util.List.of())
                : RoleHandshakeMatcher.match(manifest, buildLocalManifest());
    }

    /**
     * Builds the client's local manifest from the loaded mod set.
     *
     * <p>The hash fields are deliberately {@code null}: the client cannot
     * independently reproduce the server's definition hash, because that hash
     * folds the server-side compiled entry view (v1 baseline rows + the applied
     * {@code roleExtensionsV2} config), which the client never sees. Reporting a
     * hash would either mismatch permanently or force the client to replicate
     * server config state. So the client trusts the server for hashes (review
     * 2026-08-14 P1): the handshake gate checks report completeness, API
     * version compatibility and required-provider presence; the definition-hash
     * branch of {@link RoleHandshakeMatcher} remains for clients that DO report
     * an independent fingerprint (e.g. a future pack-aware client), it just
     * never fires for this client.
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
        return new ClientManifest(api, versions, hasPresentationResources, null, null);
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