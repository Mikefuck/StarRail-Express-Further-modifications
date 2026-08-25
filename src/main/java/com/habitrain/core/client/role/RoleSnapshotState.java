package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.network.RoleSnapshotPayload;
import com.habitrain.core.role.client.RoleClientExtensionRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Client mirror of the server's compiled role-extension entry view (fix-doc
 * §13.2), populated by {@code RoleSnapshotPayload} at join and after config
 * changes. The Mod Menu role-extension page renders {@code entries()}; HUD /
 * instinct / skin / nameplate follow the payload gameplay set.
 */
@Environment(EnvType.CLIENT)
public final class RoleSnapshotState {

    public static final RoleSnapshotState INSTANCE = new RoleSnapshotState();

    private volatile @Nullable RoleSnapshotPayload last;

    private RoleSnapshotState() {}

    public void accept(RoleSnapshotPayload payload) {
        this.last = payload;
        Set<String> active = new LinkedHashSet<>();
        Set<String> activeEntries = new LinkedHashSet<>();
        if (payload != null) {
            boolean hasGameplay = payload.gameplayEntryIds() != null
                    || payload.gameplayProviderIds() != null;
            if (hasGameplay) {
                addNonBlank(active, payload.gameplayProviderIds());
                addNonBlank(activeEntries, payload.gameplayEntryIds());
            } else if (payload.entries() != null) {
                // Pre-split payloads: HUD/instinct/skin follow live ACTIVE rows.
                for (RoleSnapshotPayload.EntryRow row : payload.entries()) {
                    if ("ACTIVE".equals(row.status()) && row.providerId() != null) {
                        active.add(row.providerId());
                        if (row.entryId() != null && !row.entryId().isBlank()) {
                            activeEntries.add(row.entryId());
                        }
                    }
                }
            }
        }
        ((RoleClientExtensionRegistry) RoleClientExtensionApi.instance())
                .setActiveProviders(active, activeEntries);
    }

    private static void addNonBlank(Set<String> into, List<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                into.add(id);
            }
        }
    }

    public @Nullable RoleSnapshotPayload get() {
        return last;
    }

    public void reset() {
        this.last = null;
        ((RoleClientExtensionRegistry) RoleClientExtensionApi.instance()).setActiveProviders(null, null);
    }
}
