package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.network.RoleSnapshotPayload;
import com.habitrain.core.role.client.RoleClientExtensionRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Client mirror of the server's compiled role-extension entry view (fix-doc
 * §13.2), populated by {@code RoleSnapshotPayload} at join and after config
 * changes. The Mod Menu role-extension page renders from here.
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
        if (payload != null && payload.entries() != null) {
            for (RoleSnapshotPayload.EntryRow row : payload.entries()) {
                if ("ACTIVE".equals(row.status()) && row.providerId() != null) {
                    active.add(row.providerId());
                    if (row.entryId() != null && !row.entryId().isBlank()) {
                        activeEntries.add(row.entryId());
                    }
                }
            }
        }
        ((RoleClientExtensionRegistry) RoleClientExtensionApi.instance())
                .setActiveProviders(active, activeEntries);
    }

    public @Nullable RoleSnapshotPayload get() {
        return last;
    }

    public void reset() {
        this.last = null;
        ((RoleClientExtensionRegistry) RoleClientExtensionApi.instance()).setActiveProviders(null, null);
    }
}
