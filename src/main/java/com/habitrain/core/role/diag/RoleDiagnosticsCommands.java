package com.habitrain.core.role.diag;

import com.habitrain.core.api.role.v2.DiagnosticAlias;
import com.habitrain.core.api.role.v2.DiagnosticEntry;
import com.habitrain.core.api.role.v2.DiagnosticReport;
import com.habitrain.core.api.role.v2.DiagnosticSnapshot;
import com.habitrain.core.api.role.v2.DiagnosticStatus;
import com.habitrain.core.api.role.v2.RoleDiagnostics;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.role.behavior.RoleHookRegistry;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import com.habitrain.core.role.action.RoleActionServiceImpl;
import com.habitrain.core.role.state.RoleStateServiceImpl;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Formats {@link RoleDiagnostics} output for {@code /habitrain roleapi}.
 *
 * <p>Pure string formatting so the command tree and the unit tests share one
 * implementation without constructing a {@code CommandSourceStack}.
 */
public final class RoleDiagnosticsCommands {

    private RoleDiagnosticsCommands() {}

    public static List<String> providers() {
        DiagnosticReport report = RoleDiagnostics.instance().report();
        List<String> lines = new ArrayList<>();
        lines.add("providers (" + report.providers().size() + ")");
        if (report.providers().isEmpty()) {
            lines.add("  (none)");
            return lines;
        }
        for (String provider : report.providers()) {
            lines.add("  " + provider);
        }
        return lines;
    }

    public static List<String> list(String filter) {
        String kind = filter == null || filter.isBlank() ? "effective" : filter.trim().toLowerCase(Locale.ROOT);
        List<DiagnosticEntry> entries = RoleDiagnostics.instance().entries();
        List<String> lines = new ArrayList<>();
        int shown = 0;
        for (DiagnosticEntry entry : entries) {
            if (!matchesListFilter(entry, kind)) {
                continue;
            }
            shown++;
            lines.add(formatEntry(entry));
        }
        lines.add(0, "entries [" + kind + "] " + shown + "/" + entries.size());
        if (shown == 0) {
            lines.add("  (none)");
        }
        return lines;
    }

    public static List<String> inspect(String roleId) {
        RoleKey key = RoleKey.tryParse(roleId);
        List<String> lines = new ArrayList<>();
        lines.add("inspect " + roleId);
        if (key == null) {
            lines.add("  invalid role id");
            return lines;
        }
        boolean found = false;
        for (DiagnosticEntry entry : RoleDiagnostics.instance().entries()) {
            if (matchesRole(entry, key)) {
                found = true;
                lines.add(formatEntry(entry));
            }
        }
        if (!found) {
            lines.add("  no v2 entries for " + key);
        }
        return lines;
    }

    public static List<String> aliases(String roleId) {
        RoleKey key = roleId == null || roleId.isBlank() ? null : RoleKey.tryParse(roleId);
        List<String> lines = new ArrayList<>();
        int shown = 0;
        for (DiagnosticAlias alias : RoleDiagnostics.instance().aliases()) {
            if (key != null && !alias.from().equals(key) && !alias.to().equals(key)) {
                continue;
            }
            shown++;
            lines.add("  " + alias.from() + " -> " + alias.to()
                    + " [" + (alias.valid() ? "VALID" : "INVALID") + "]"
                    + (alias.message() == null ? "" : " " + alias.message()));
        }
        lines.add(0, "aliases " + shown);
        if (shown == 0) {
            lines.add("  (none)");
        }
        return lines;
    }

    public static List<String> snapshot() {
        DiagnosticSnapshot info = RoleDiagnostics.instance().snapshotInfo();
        RoleSnapshot current = RoleSnapshotManager.INSTANCE.current();
        List<String> lines = new ArrayList<>();
        lines.add("snapshot " + info.id().version()
                + " roles=" + info.roleCount()
                + " replaced=" + info.replacedCount()
                + " aliases=" + info.aliasCount());
        if (current != null) {
            lines.add("  live=" + (RoleSnapshotManager.INSTANCE.round() != null ? "round" : "lobby"));
        } else {
            lines.add("  live=none");
        }
        return lines;
    }

    public static List<String> state(@Nullable UUID playerId, String roleId) {
        RoleKey key = roleId == null || roleId.isBlank() ? null : RoleKey.tryParse(roleId);
        List<String> lines = new ArrayList<>();
        lines.add("state"
                + (playerId == null ? "" : " player=" + playerId)
                + (key == null ? "" : " role=" + key));
        if (roleId != null && !roleId.isBlank() && key == null) {
            lines.add("  invalid role id");
            return lines;
        }
        RoleStateServiceImpl store = (RoleStateServiceImpl) com.habitrain.core.api.role.v2.state.RoleStateApi.instance();
        List<String> rows = store.describe(playerId, key);
        if (rows.isEmpty()) {
            lines.add("  (none)");
        } else {
            for (String row : rows) {
                lines.add("  " + row);
            }
        }
        return lines;
    }

    public static List<String> hooks(String roleId) {
        RoleKey key = RoleKey.tryParse(roleId);
        List<String> lines = new ArrayList<>();
        lines.add("hooks " + roleId);
        if (key == null) {
            lines.add("  invalid role id");
            return lines;
        }
        RoleHooks hooks = RoleHookRegistry.INSTANCE.get(key);
        if (hooks == null) {
            lines.add("  (none)");
            return lines;
        }
        lines.add("  lifecycle=" + (hooks.lifecycle() != null));
        lines.add("  combat=" + (hooks.combat() != null));
        lines.add("  tick=" + (hooks.tick() != null));
        lines.add("  interaction=" + (hooks.interaction() != null));
        lines.add("  shop=" + (hooks.shop() != null));
        lines.add("  task=" + (hooks.task() != null));
        lines.add("  meeting=" + (hooks.meeting() != null));
        lines.add("  win=" + (hooks.win() != null));
        return lines;
    }

    public static List<String> perf() {
        return com.habitrain.core.role.behavior.RoleEventDispatcher.INSTANCE.describePerf();
    }

    public static List<String> archive() {
        return com.habitrain.core.role.snapshot.RoleSnapshotArchive.INSTANCE.describe();
    }

    public static List<String> legacy() {
        return com.habitrain.core.role.legacy.LegacyRoleScan.INSTANCE.describe();
    }

    public static List<String> capabilities() {
        List<String> lines = new ArrayList<>();
        lines.add("capabilities");
        com.habitrain.core.role.capability.RoleCapabilityServiceImpl store =
                (com.habitrain.core.role.capability.RoleCapabilityServiceImpl)
                        com.habitrain.core.api.role.v2.capability.RoleCapabilityApi.instance();
        for (String row : store.describe()) {
            lines.add("  " + row);
        }
        return lines;
    }

    public static List<String> actions(String roleId) {
        RoleKey key = roleId == null || roleId.isBlank() ? null : RoleKey.tryParse(roleId);
        List<String> lines = new ArrayList<>();
        lines.add("actions" + (key == null ? "" : " role=" + key));
        if (roleId != null && !roleId.isBlank() && key == null) {
            lines.add("  invalid role id");
            return lines;
        }
        RoleActionServiceImpl store = (RoleActionServiceImpl) com.habitrain.core.api.role.v2.action.RoleActionApi.instance();
        List<String> rows = store.describe(key);
        if (rows.isEmpty()) {
            lines.add("  (none)");
        } else {
            for (String row : rows) {
                lines.add("  " + row);
            }
        }
        return lines;
    }

    public static List<String> trace(String roleId, String field) {
        RoleKey key = RoleKey.tryParse(roleId);
        List<String> lines = new ArrayList<>();
        lines.add("trace " + roleId + " " + field);
        if (key == null) {
            lines.add("  invalid role id");
            return lines;
        }
        lines.addAll(RoleFieldTrace.trace(key, field));
        return lines;
    }

    private static boolean matchesListFilter(DiagnosticEntry entry, String kind) {
        return switch (kind) {
            case "effective", "all", "active" -> entry.status() == DiagnosticStatus.ACTIVE;
            case "disabled" -> entry.status() == DiagnosticStatus.DISABLED;
            case "conflict" -> entry.status() == DiagnosticStatus.CONFLICT;
            case "invalid" -> entry.status() == DiagnosticStatus.INVALID;
            case "legacy" -> entry.status() == DiagnosticStatus.LEGACY_UNMANAGED;
            case "broken", "circuit" -> entry.status() == DiagnosticStatus.HOOK_CIRCUIT_BROKEN;
            default -> true;
        };
    }

    private static boolean matchesRole(DiagnosticEntry entry, RoleKey key) {
        if (entry.target() != null && entry.target().equals(key)) {
            return true;
        }
        return key.toString().equals(entry.id()) || key.location().toString().equals(entry.id());
    }

    private static String formatEntry(DiagnosticEntry entry) {
        StringBuilder sb = new StringBuilder("  ").append(entry.kind()).append(' ')
                .append(entry.id()).append(" [").append(entry.status()).append(']');
        if (entry.enabledSource() != null) {
            sb.append(" via=").append(entry.enabledSource());
        }
        if (entry.providerId() != null && !entry.providerId().isBlank()) {
            sb.append(" provider=").append(entry.providerId());
        }
        if (entry.conflictFields() != null) {
            sb.append(" conflict=").append(entry.conflictFields());
        }
        if (entry.definitionHash() != null) {
            sb.append(" hash=").append(entry.definitionHash());
        }
        if (entry.message() != null) {
            sb.append(' ').append(entry.message());
        }
        return sb.toString();
    }
}
