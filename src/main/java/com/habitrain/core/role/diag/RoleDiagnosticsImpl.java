package com.habitrain.core.role.diag;

import com.habitrain.core.api.role.v2.DiagnosticAlias;
import com.habitrain.core.api.role.v2.DiagnosticEntry;
import com.habitrain.core.api.role.v2.DiagnosticReport;
import com.habitrain.core.api.role.v2.DiagnosticSnapshot;
import com.habitrain.core.api.role.v2.DiagnosticStatus;
import com.habitrain.core.api.role.v2.RoleDiagnostics;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.api.role.v2.RoleSnapshotId;
import com.habitrain.core.api.role.v2.definition.RoleAlias;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleExtensionCompiler;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Default {@link RoleDiagnostics} implementation. Every v2 entry's status is a
 * <em>compiled</em> result (fix-doc §13.2) read from
 * {@link RoleExtensionRegistry#getCompiledEntries()}, which folds validation,
 * the v1/v2 conflict analyzer and the live {@code roleExtensionsV2} config — the
 * diagnostic never guesses {@code ACTIVE} from mere registration.
 */
public final class RoleDiagnosticsImpl implements RoleDiagnostics {

    public RoleDiagnosticsImpl() {}

    @Override
    public DiagnosticReport report() {
        return new DiagnosticReport(providers(), entries(), aliases(), snapshotInfo());
    }

    @Override
    public List<DiagnosticEntry> entries() {
        List<DiagnosticEntry> out = new ArrayList<>();
        for (ManagedRoleEntry<?> entry : RoleExtensionRegistry.INSTANCE.getCompiledEntries()) {
            out.add(fromCompiled(entry));
        }
        for (var hit : com.habitrain.core.role.legacy.LegacyRoleScan.INSTANCE.hits()) {
            out.add(new DiagnosticEntry("LEGACY", hit.key().toString(), hit.key(),
                    DiagnosticStatus.LEGACY_UNMANAGED, hit.source()));
        }
        for (var row : com.habitrain.core.role.behavior.RoleEventDispatcher.INSTANCE.perf()) {
            if (row.broken()) {
                out.add(new DiagnosticEntry("HOOK", row.role() + "#" + row.hook(), row.role(),
                        DiagnosticStatus.HOOK_CIRCUIT_BROKEN,
                        "circuit broken after " + row.failures() + " failures"));
            }
        }
        return out;
    }

    /** Maps a compiled entry shell to a rich diagnostic row (§13.2). */
    private static DiagnosticEntry fromCompiled(ManagedRoleEntry<?> entry) {
        DiagnosticStatus status = toDiagnosticStatus(entry.status());
        String message = entry.statusMessage();
        if (status == DiagnosticStatus.ACTIVE) {
            if (entry.operation() == com.habitrain.core.role.extension.RoleOperation.REPLACE
                    && entry.declaration() instanceof com.habitrain.core.api.role.v2.definition.RoleReplacement repl
                    && RoleExtensionRegistry.INSTANCE.compiledReplacement(repl) == null) {
                status = DiagnosticStatus.INVALID;
                message = "replacement not compiled";
            }
            if (entry.operation() == com.habitrain.core.role.extension.RoleOperation.ALIAS
                    && entry.declaration() instanceof RoleAlias alias
                    && !aliasTargetExists(alias.to())) {
                status = DiagnosticStatus.INVALID;
                message = "alias target does not exist: " + alias.to();
            }
        }
        return new DiagnosticEntry(
                entry.operation() == null ? "ENTRY" : entry.operation().name(),
                entry.entryId(),
                entry.target(),
                status,
                message,
                entry.providerId(),
                entry.entryId(),
                enabledSource(entry),
                conflictFields(entry),
                definitionHash(entry));
    }

    private static DiagnosticStatus toDiagnosticStatus(EntryStatus status) {
        return switch (status) {
            case ACTIVE -> DiagnosticStatus.ACTIVE;
            case INVALID -> DiagnosticStatus.INVALID;
            case CONFLICT -> DiagnosticStatus.CONFLICT;
            case DISABLED -> DiagnosticStatus.DISABLED;
            case LEGACY_UNMANAGED -> DiagnosticStatus.LEGACY_UNMANAGED;
        };
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

    /** For a MODIFY entry, the fields that carry a configured conflict winner. */
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
        if (declaration == null) {
            return null;
        }
        return Integer.toHexString(declaration.hashCode());
    }

    @Override
    public List<DiagnosticAlias> aliases() {
        List<DiagnosticAlias> out = new ArrayList<>();
        for (RoleAlias alias : RoleExtensionRegistry.INSTANCE.getAliases()) {
            boolean valid = aliasTargetExists(alias.to());
            out.add(new DiagnosticAlias(alias.from(), alias.to(), valid,
                    valid ? null : "alias target does not exist: " + alias.to()));
        }
        return out;
    }

    @Override
    public DiagnosticSnapshot snapshotInfo() {
        RoleSnapshot current = RoleSnapshotManager.INSTANCE.current();
        RoleSnapshot pending = RoleSnapshotManager.INSTANCE.pending();
        if (current == null) {
            return new DiagnosticSnapshot(new RoleSnapshotId(0), 0, 0, 0,
                    pending == null ? null : pending.id());
        }
        return new DiagnosticSnapshot(current.id(), current.roles().size(),
                current.replacedTargets().size(), current.aliases().size(),
                pending == null ? null : pending.id());
    }

    private Set<String> providers() {
        return new LinkedHashSet<>(RoleExtensionRegistry.INSTANCE.getProviders());
    }

    private static boolean aliasTargetExists(RoleKey to) {
        RoleSnapshot current = RoleSnapshotManager.INSTANCE.current();
        if (current != null && current.isActive(to.location())) {
            return true;
        }
        return RoleExtensionRegistry.INSTANCE.isAdded(to.location())
                || RoleExtensionRegistry.INSTANCE.getCompiledReplacements().containsKey(to.location());
    }
}
