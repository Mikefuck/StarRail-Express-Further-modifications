package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.book.RoleBookPage;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Diagnostic carrier for a legacy (v1) {@code MODIFY} definition translated into
 * the unified model (fix-doc §15.1). It records the target, provider,
 * the un-reversible-registrar flag, the role-book appendices, and — after the
 * v1-parity work — a real v2 {@link RolePatch} containing every translatable
 * v1 field operation. Consumers that only need conflict/diagnostic data can
 * ignore the translated patch; consumers that need a v2-compatible declaration
 * can use it directly.
 */
public record RolePatchBundle(
        RoleKey target,
        String sourceModId,
        boolean hasRawRegistrar,
        List<RoleBookPage> roleBookAppendices,
        @Nullable RolePatch translatedPatch) {

    public RolePatchBundle {
        roleBookAppendices = List.copyOf(roleBookAppendices);
    }

    /** Backwards-compatible shell constructor used when no v2 patch is available. */
    public RolePatchBundle(
            RoleKey target,
            String sourceModId,
            boolean hasRawRegistrar,
            List<RoleBookPage> roleBookAppendices) {
        this(target, sourceModId, hasRawRegistrar, roleBookAppendices, null);
    }
}
