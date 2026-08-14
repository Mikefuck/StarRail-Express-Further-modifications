package com.habitrain.core.role.config;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The versioned {@code roleExtensionsV2} config section (fix-doc §13.1).
 *
 * <p>Server-authoritative: persisted by {@link RoleExtensionConfigService} and
 * edited only through OP4 commands / the OP gated client page. The section shape
 * mirrors the design guide:
 *
 * <pre>{@code
 * "roleExtensionsV2": {
 *   "enabled": true,
 *   "providers": { "example": true },
 *   "entries": { "example$buff@sre:vigilante": true },
 *   "conflictWinners": { "sre:vigilante#spawn.defaultMax": "example$buff@sre:vigilante" },
 *   "allowGlobalHooks": false
 * }
 * }</pre>
 *
 * <p>Resolution is additive: a provider/entry is enabled unless explicitly
 * disabled; a provider-level {@code false} gates every entry it owns; the global
 * {@code enabled} switch gates everything. Entry IDs are the core-owned ids from
 * {@link com.habitrain.core.role.extension.ManagedRoleEntry#entryId()}.
 */
public final class RoleExtensionConfigSection {

    /** Current schema version. */
    public static final int VERSION = 1;

    private boolean enabled = true;
    private final Map<String, Boolean> providers = new LinkedHashMap<>();
    private final Map<String, Boolean> entries = new LinkedHashMap<>();
    private final Map<String, String> conflictWinners = new LinkedHashMap<>();
    private boolean allowGlobalHooks = false;

    /** A deep copy of the default (all-enabled) section. */
    public static RoleExtensionConfigSection createDefault() {
        return new RoleExtensionConfigSection();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Unmodifiable view of the provider toggles (presence + value). */
    public Map<String, Boolean> providers() {
        return Map.copyOf(providers);
    }

    /** Unmodifiable view of the entry toggles. */
    public Map<String, Boolean> entries() {
        return Map.copyOf(entries);
    }

    /** Unmodifiable view of the conflict winners ({@code target#field} -> winning entryId). */
    public Map<String, String> conflictWinners() {
        return Map.copyOf(conflictWinners);
    }

    public boolean isAllowGlobalHooks() {
        return allowGlobalHooks;
    }

    public void setAllowGlobalHooks(boolean allowGlobalHooks) {
        this.allowGlobalHooks = allowGlobalHooks;
    }

    // ------------------------------------------------------------------
    // Mutators (kept additive: setting true restores the default)
    // ------------------------------------------------------------------

    public void setProviderEnabled(String providerId, boolean on) {
        if (on) {
            providers.remove(providerId);
        } else {
            providers.put(providerId, false);
        }
    }

    public void setEntryEnabled(String entryId, boolean on) {
        if (on) {
            entries.remove(entryId);
        } else {
            entries.put(entryId, false);
        }
    }

    /** Sets the field winner for {@code target#field}. A null/blank winner clears it. */
    public void setConflictWinner(String targetField, @Nullable String winnerEntryId) {
        if (targetField == null || targetField.isBlank()) {
            return;
        }
        if (winnerEntryId == null || winnerEntryId.isBlank()) {
            conflictWinners.remove(targetField);
        } else {
            conflictWinners.put(targetField, winnerEntryId);
        }
    }

    /** Replaces the whole section with the given one (C2S sync path). */
    public void replaceWith(RoleExtensionConfigSection other) {
        if (other == null) {
            return;
        }
        this.enabled = other.enabled;
        this.providers.clear();
        this.providers.putAll(other.providers);
        this.entries.clear();
        this.entries.putAll(other.entries);
        this.conflictWinners.clear();
        this.conflictWinners.putAll(other.conflictWinners);
        this.allowGlobalHooks = other.allowGlobalHooks;
    }

    /** A deep copy of this section. */
    public RoleExtensionConfigSection copy() {
        RoleExtensionConfigSection c = new RoleExtensionConfigSection();
        c.enabled = enabled;
        c.providers.putAll(providers);
        c.entries.putAll(entries);
        c.conflictWinners.putAll(conflictWinners);
        c.allowGlobalHooks = allowGlobalHooks;
        return c;
    }
}
