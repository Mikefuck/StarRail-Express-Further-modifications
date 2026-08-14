package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import io.wifi.starrailexpress.api.SRERole;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;

/**
 * The central "current {@link CompiledModifyOverlay} for this role object" path.
 * SRE-facing getters (and the optional getter Mixin) consult it so a modified
 * role's getter-based values are effective without replacing the object.
 */
public final class RoleOverlayAccessor {

    private static final IdentityHashMap<SRERole, CompiledModifyOverlay> OVERLAYS = new IdentityHashMap<>();

    private RoleOverlayAccessor() {}

    /** The current overlay for {@code role}, or {@code null} when not modified. */
    public static @Nullable CompiledModifyOverlay currentOverlay(SRERole role) {
        return role == null ? null : OVERLAYS.get(role);
    }

    /** Whether {@code role} currently carries an active v2 MODIFY overlay. */
    public static boolean isModified(SRERole role) {
        return role != null && OVERLAYS.containsKey(role);
    }

    /** Registers (or replaces) the overlay for a role object. */
    public static void set(SRERole role, CompiledModifyOverlay overlay) {
        if (role != null) {
            OVERLAYS.put(role, overlay);
        }
    }

    /** Removes the overlay for a role object (restore path). */
    public static void remove(SRERole role) {
        if (role != null) {
            OVERLAYS.remove(role);
        }
    }

    /** Drops every overlay (server stop, snapshot switch, test isolation). */
    public static void clear() {
        OVERLAYS.clear();
    }
}
