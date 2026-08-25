package com.habitrain.core.api.role.v2;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Optional;

/**
 * Public, read-only directory of effective roles (v2 Role Extension platform).
 *
 * <p>The catalog is the single place any consumer should enumerate, look up,
 * canonicalize and resolve roles. It reflects the currently compiled override
 * snapshot plus the effective visible role set, so replaced/hidden roles never
 * leak into candidate lists and active replacements appear exactly once. It also
 * resolves v2 {@code ALIAS} redirects and surfaces v2 {@code MODIFY}/{@code REPLACE}
 * results.
 */
public interface RoleCatalogApi {

    /** The process-wide catalog instance. */
    static RoleCatalogApi instance() {
        return DefaultHolder.INSTANCE;
    }

    /** Lazily-bound default instance; avoids initializing {@code TMMRoles} on class load. */
    final class DefaultHolder {
        private DefaultHolder() {}

        static final RoleCatalogApi INSTANCE =
                com.habitrain.core.role.catalog.RoleCatalogImpl.defaultInstance();
    }

    /**
     * Resolves a key (or an alias / replacement target of it) to its effective
     * role, if the role is live in the current snapshot.
     *
     * @return the effective role, or empty if no live role resolves
     */
    Optional<EffectiveRole> find(RoleKey keyOrAlias);

    /**
     * Maps any raw or alias role id to its canonical {@link RoleKey}, following
     * a replacement redirect and the inactive-managed fallback.
     */
    RoleKey canonicalize(ResourceLocation id);

    /**
     * The full effective role set: visible baseline roles plus active
     * replacements, each exactly once.
     */
    default Collection<EffectiveRole> effectiveRoles() {
        return effectiveRoles(RoleQuery.generic());
    }

    /**
     * The effective role set filtered, sorted and (optionally) widened by the
     * given {@link RoleQuery}. The unfiltered result is a stable superset of
     * {@link #effectiveRoles()}: it is the same visible set unless the query
     * opts in to surfacing replaced baselines via {@link RoleQuery.Builder#includeReplaced}.
     */
    Collection<EffectiveRole> effectiveRoles(RoleQuery query);

    /** Resolves a raw upstream role to its effective view; empty if it is hidden. */
    Optional<EffectiveRole> resolve(SRERole rawRole);

    /**
     * Resolves a stored id — new {@code namespace:path} or legacy path-only —
     * to its effective role, following active replacements and alias redirects.
     */
    Optional<EffectiveRole> resolveStored(String storedValue);

    /**
     * Whether the key has an effective (live) role in the catalog. A replaced
     * target resolves under the replacement identity, so it is {@code true} for
     * the target key too.
     */
    boolean isActive(RoleKey key);

    /** Whether the key's role is currently hidden by an active replacement. */
    boolean isReplaced(RoleKey key);

    /** Whether the key's role is currently modified by an active modify definition. */
    boolean isModified(RoleKey key);

    /**
     * Whether the key's role was added through the v2 ADD model.
     */
    boolean isAdded(RoleKey key);

    /** The version of the currently compiled snapshot. */
    RoleSnapshotId snapshot();

    /**
     * The frozen {@link RoleSnapshot} currently in effect: the in-progress
     * round snapshot while a round is live, otherwise the just-ended settlement
     * snapshot until pending is promoted, otherwise the lobby snapshot.
     *
     * <p>During other mods' {@code OnGameEnd} (after core's dispatcher), this is
     * still the just-ended catalog until the next server tick promotes pending.
     * Downstream settlement code should prefer {@link #lastEndedSnapshot()} or
     * {@code currentSnapshot()}, never assume the live lobby overlay.
     */
    default Optional<RoleSnapshot> currentSnapshot() {
        return Optional.empty();
    }

    /**
     * The in-progress round snapshot only. Empty in lobby and after
     * {@code endRound()} has cleared the round slot.
     */
    default Optional<RoleSnapshot> roundSnapshot() {
        return Optional.empty();
    }

    /**
     * The just-ended settlement snapshot held until pending is promoted.
     * Empty when no settlement is held (lobby, live round, or after
     * {@code activatePending()}).
     */
    default Optional<RoleSnapshot> lastEndedSnapshot() {
        return Optional.empty();
    }

    /**
     * Restores an effective role from an archived snapshot (replay / history).
     * Empty when the snapshot id is unknown or the key is not in that
     * generation. Does not fall back to the live current snapshot.
     */
    Optional<EffectiveRole> restore(RoleSnapshotId snapshot, RoleKey key);
}
