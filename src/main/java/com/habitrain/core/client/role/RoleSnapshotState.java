package com.habitrain.core.client.role;

import com.habitrain.core.network.RoleSnapshotPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

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
    }

    public @Nullable RoleSnapshotPayload get() {
        return last;
    }

    public void reset() {
        this.last = null;
    }
}
