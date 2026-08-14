package com.habitrain.core.role.diag;

import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.config.RoleManifest;
import com.habitrain.core.role.config.RoleManifestService;
import com.habitrain.core.role.config.RoleProviderManifest;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleExtensionRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Formats the {@code /habitrain roleapi config ...} and {@code manifest} output.
 * Pure string formatting (plus the server-side manifest gather) so the command
 * tree and unit tests share one implementation.
 */
public final class RoleConfigCommands {

    private RoleConfigCommands() {}

    /** The current {@code roleExtensionsV2} config as display lines. */
    public static List<String> status() {
        RoleExtensionConfigService cfg = RoleExtensionConfigService.INSTANCE;
        List<String> lines = new ArrayList<>();
        lines.add("roleExtensionsV2 enabled=" + cfg.isEnabled()
                + " allowGlobalHooks=" + cfg.isAllowGlobalHooks());

        lines.add("providers (" + RoleExtensionRegistry.INSTANCE.getProviders().size() + ")");
        for (String provider : new TreeSet<>(RoleExtensionRegistry.INSTANCE.getProviders())) {
            lines.add("  " + provider + ": " + (cfg.isProviderEnabled(provider) ? "on" : "off"));
        }

        lines.add("entries (explicit toggles " + cfg.section().entries().size() + ")");
        for (Map.Entry<String, Boolean> e : sorted(cfg.section().entries())) {
            lines.add("  " + e.getKey() + ": " + (e.getValue() ? "on" : "off"));
        }

        lines.add("conflictWinners (" + cfg.section().conflictWinners().size() + ")");
        for (Map.Entry<String, String> e : sortedWinners(cfg.section().conflictWinners())) {
            lines.add("  " + e.getKey() + " -> " + e.getValue());
        }

        Map<EntryStatus, Integer> counts = new LinkedHashMap<>();
        for (ManagedRoleEntry<?> entry : RoleExtensionRegistry.INSTANCE.getCompiledEntries()) {
            counts.merge(entry.status(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("compiled:");
        counts.forEach((status, n) -> sb.append(' ').append(status).append('=').append(n));
        lines.add(sb.toString());
        return lines;
    }

    /** The current server manifest as display lines (production: touches FabricLoader). */
    public static List<String> manifest() {
        RoleManifest m = RoleManifestService.build();
        List<String> lines = new ArrayList<>();
        lines.add("manifest api=" + m.coreApiVersion());
        lines.add("  definitionHash=" + m.definitionHash());
        lines.add("  presentationHash=" + m.presentationHash());
        lines.add("  lobby=" + m.lobbySnapshotId()
                + (m.roundSnapshotId() == null ? " round=none" : " round=" + m.roundSnapshotId()));
        lines.add("  capabilities=" + String.join(",", new TreeSet<>(m.capabilities())));
        lines.add("  providers (" + m.providers().size() + ")");
        for (RoleProviderManifest p : m.providers()) {
            lines.add("    " + p.providerId() + "@" + p.version()
                    + (p.requiredClient() ? " requiredClient" : ""));
        }
        lines.add("  config=" + m.configJson());
        return lines;
    }

    private static List<Map.Entry<String, Boolean>> sorted(Map<String, Boolean> map) {
        List<Map.Entry<String, Boolean>> out = new ArrayList<>(map.entrySet());
        out.sort(Comparator.comparing(Map.Entry::getKey));
        return out;
    }

    private static List<Map.Entry<String, String>> sortedWinners(Map<String, String> map) {
        List<Map.Entry<String, String>> out = new ArrayList<>(map.entrySet());
        out.sort(Comparator.comparing(Map.Entry::getKey));
        return out;
    }
}
