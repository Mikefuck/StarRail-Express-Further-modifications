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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the S2C {@link RoleSnapshotPayload} from the compiled entry view, the
 * lobby/round/pending snapshot slots and the live config, so the client Mod
 * Menu page mirrors the server diagnostics (fix-doc §13.2).
 *
 * <p>{@code lobbySnapshotId} is always {@link RoleSnapshotManager#lobby()},
 * never {@link RoleSnapshotManager#current()}. Mid-round config must not be
 * advertised as mutating the live round; {@code roundSnapshotId} identifies
 * the frozen round when one is in progress.
 *
 * <p>{@code entries} stay live diagnostics. The gameplay id lists are sourced
 * from {@link RoleSnapshotManager#current()}{@code .enabledBehaviorEntries}
 * when a snapshot is published. Live {@code ACTIVE} diagnostic rows are
 * unioned only in lobby (no round and no pending); a live round or a
 * settlement that still has a pending snapshot stays on the frozen set.
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
        RoleSnapshot current = RoleSnapshotManager.INSTANCE.current();
        RoleSnapshot lobby = RoleSnapshotManager.INSTANCE.lobby();
        RoleSnapshot round = RoleSnapshotManager.INSTANCE.round();
        RoleSnapshot pending = RoleSnapshotManager.INSTANCE.pending();
        Set<String> gameplayProviders = new LinkedHashSet<>();
        Set<String> gameplayEntries = new LinkedHashSet<>();
        if (current != null) {
            for (RoleSnapshot.BehaviorEntry be : current.enabledBehaviorEntries()) {
                addGameplay(be.providerId(), be.entryId(), gameplayProviders, gameplayEntries);
            }
        }
        if (round == null && pending == null) {
            for (EntryRow row : rows) {
                addLiveActive(row, gameplayProviders, gameplayEntries);
            }
        }
        return new RoleSnapshotPayload(
                rows,
                lobby == null ? "none" : lobby.id().toString(),
                round == null ? null : round.id().toString(),
                pending == null ? null : pending.id().toString(),
                RoleManifestHashes.definitionHash(),
                RoleExtensionConfigService.INSTANCE.toJsonString(),
                new ArrayList<>(gameplayEntries),
                new ArrayList<>(gameplayProviders));
    }

    /**
     * Index shape consumed by {@code RoleClientExtensionRegistry#isActive}:
     * {@code provider$entry}, plus a trailing {@code @} so the existing
     * {@code provider$entryKey@} prefix match succeeds without a target suffix.
     */
    static String gameplayEntryId(String providerId, String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return null;
        }
        if (entryId.indexOf('$') > 0) {
            return entryId;
        }
        if (providerId == null || providerId.isBlank()) {
            return entryId;
        }
        return providerId + "$" + entryId;
    }

    private static void addGameplay(String providerId, String entryId,
                                    Set<String> providers, Set<String> entries) {
        if (providerId != null && !providerId.isBlank()) {
            providers.add(providerId);
        }
        if (entryId != null && !entryId.isBlank()) {
            entries.add(entryId);
        }
        String keyed = gameplayEntryId(providerId, entryId);
        if (keyed == null) {
            return;
        }
        entries.add(keyed);
        if (keyed.indexOf('@') < 0) {
            entries.add(keyed + "@");
        }
    }

    private static void addLiveActive(EntryRow row, Set<String> providers, Set<String> entries) {
        if (row == null || !"ACTIVE".equals(row.status())) {
            return;
        }
        if (row.providerId() != null && !row.providerId().isBlank()) {
            providers.add(row.providerId());
        }
        if (row.entryId() != null && !row.entryId().isBlank()) {
            entries.add(row.entryId());
        }
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
