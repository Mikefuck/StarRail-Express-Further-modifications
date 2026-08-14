package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.book.RoleBookPage;
import com.habitrain.core.api.role.v2.RoleKey;

import java.util.List;

/**
 * Diagnostic carrier for a legacy (v1) {@code MODIFY} definition translated into
 * the unified model (fix-doc §15.1). Full field-level translation of the v1 patch
 * set is completed in Phase G; this first version records the target, provider,
 * the un-reversible-registrar flag and the role-book appendices so the conflict
 * analyzer and diagnostics can reason about the declaration.
 */
public record RolePatchBundle(
        RoleKey target,
        String sourceModId,
        boolean hasRawRegistrar,
        List<RoleBookPage> roleBookAppendices) {}
