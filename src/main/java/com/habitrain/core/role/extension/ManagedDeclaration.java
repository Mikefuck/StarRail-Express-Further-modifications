package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleKey;

import java.util.Objects;

/**
 * Provider-scoped ownership for {@code action} / {@code state} / {@code voice} /
 * {@code chat} declarations (audit P1-2). The provider transaction stages these
 * instead of raw declarations, so config gating, diagnostics and the manifest
 * share one ownership model and a disabled provider/entry cannot be bypassed.
 *
 * <p>{@code entryId} is the id the v2 config uses to disable the entry
 * (currently the declaration's own namespaced {@code id}, since
 * {@code requireOwnedId} already forces the provider namespace).
 *
 * <p>Internal ownership model — deliberately not part of the stable public v2
 * API documentation.
 *
 * @param providerId  the owning provider mod id
 * @param entryId     the config-scoped entry id
 * @param role        the role the declaration is bound to
 * @param declaration the raw declaration (spec / policy)
 */
public record ManagedDeclaration<T>(String providerId, String entryId, RoleKey role, T declaration) {

    public ManagedDeclaration {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(declaration, "declaration");
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (entryId.isBlank()) {
            throw new IllegalArgumentException("entryId must not be blank");
        }
    }
}
