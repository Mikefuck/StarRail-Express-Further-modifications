package com.habitrain.core.role.config;

import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.network.RoleSnapshotPayload;
import com.habitrain.core.network.RoleSnapshotPayload.EntryRow;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleExtensionCompiler;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import com.habitrain.core.api.role.v2.definition.RolePatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the S2C {@link RoleSnapshotPayload} from the compiled entry view, the
 * current snapshot and the live config, so the client Mod Menu page mirrors the
 * server diagnostics (fix-doc §13.2).
 */
public final class RoleSnapshotService {

    private RoleSnapshotService() {}

    public static RoleSnapshotPayload build() {
        List<EntryRow> rows = new ArrayList<>();
        for (ManagedRoleEntry<?> entry : RoleExtensionRegistry.INSTANCE.getCompiledEntries()) {
            rows.add(new EntryRow(
                    entry.entryId(),
                    entry.providerId(),
                    entry.operation() == null ? "" : entry.operation().name(),
                    entry.target() == null ? entry.entryId() : entry.target().toString(),
                    entry.status().name(),
                    entry.statusMessage(),
                    enabledSource(entry),
                    conflictFields(entry),
                    definitionHash(entry)));
        }
        RoleSnapshot snap = RoleSnapshotManager.INSTANCE.current();
        RoleSnapshot round = RoleSnapshotManager.INSTANCE.round();
        return new RoleSnapshotPayload(
                rows,
                snap == null ? "none" : snap.id().toString(),
                round == null ? null : round.id().toString(),
                RoleManifestHashes.definitionHash(),
                RoleExtensionConfigService.INSTANCE.toJsonString());
    }

    private static String enabledSource(ManagedRoleEntry<?> entry) {
        if (entry.legacy()) {
            return "legacy";
        }
        return switch (RoleExtensionConfigService.INSTANCE.gateFor(entry.providerId(), entry.entryId())) {
            case ENABLED -> "active";
            case GLOBAL_DISABLED -> "global";
            case PROVIDER_DISABLED -> "provider";
            case ENTRY_DISABLED -> "entry";
        };
    }

    private static String conflictFields(ManagedRoleEntry<?> entry) {
        Object declaration = entry.declaration();
        if (!(declaration instanceof RolePatch patch)) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        for (String field : RoleExtensionCompiler.fieldsSetBy(patch)) {
            if (RoleExtensionConfigService.INSTANCE.winnerFor(
                    patch.target().location(), field) != null) {
                fields.add(field);
            }
        }
        return fields.isEmpty() ? null : String.join(",", fields);
    }

    private static String definitionHash(ManagedRoleEntry<?> entry) {
        Object declaration = entry.declaration();
        return declaration == null ? null : Integer.toHexString(declaration.hashCode());
    }
}
