package com.habitrain.core.role.catalog;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.QueryPurpose;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleOrdering;
import com.habitrain.core.api.role.v2.RoleQuery;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.api.role.v2.RoleSnapshotId;
import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.override.RoleOverrideEngine;
import com.habitrain.core.role.snapshot.RoleSnapshotArchive;
import com.habitrain.core.role.snapshot.RoleSnapshotCompiler;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import com.habitrain.core.role.snapshot.RoleSnapshotVersions;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Internal read-only implementation of {@link RoleCatalogApi}, backed by the
 * override engine's compiled snapshot, the override resolver and the v2 role
 * extension registry ({@code MODIFY}/{@code REPLACE}/{@code ALIAS}).
 *
 * <p>Not part of the public API; obtain the facade through
 * {@link RoleCatalogApi#instance()}. The raw role source is injected through a
 * {@link RawRoleLookup} so the directory logic is unit-testable without touching
 * {@code TMMRoles}'s static initializer (which needs a bootstrapped game).
 */
@SuppressWarnings("deprecation") // Retained for legacy API compatibility.
public final class RoleCatalogImpl implements RoleCatalogApi {

    /** The process-wide instance bound to the live upstream role registry. */
    public static RoleCatalogImpl defaultInstance() {
        return new RoleCatalogImpl(TmmRoleLookup.INSTANCE);
    }

    private final RawRoleLookup lookup;

    /** 审核 R-22：TEMP 编译快照缓存与其失效判据（见 {@link #tempVersion()}）。 */
    private volatile @org.jetbrains.annotations.Nullable RoleSnapshot cachedTempSnapshot;
    private volatile long cachedTempVersion = Long.MIN_VALUE;

    /** @param rawRoles the raw upstream role map this directory reads */
    public RoleCatalogImpl(Map<ResourceLocation, SRERole> rawRoles) {
        this(new MapRoleLookup(Objects.requireNonNull(rawRoles, "rawRoles")));
    }

    /** @param lookup the raw role source this directory reads */
    public RoleCatalogImpl(RawRoleLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    private static RoleOverrideEngine engine() {
        return RoleOverrideEngine.getInstance();
    }

    @Override
    public Collection<EffectiveRole> effectiveRoles(RoleQuery query) {
        Objects.requireNonNull(query, "query");

        RoleSnapshot snap = compiledSnapshot();
        List<EffectiveRole> result = new ArrayList<>();
        Set<RoleKey> seen = new LinkedHashSet<>();

        for (EffectiveRole er : snap.effectiveRoles()) {
            if (matches(er, query)) {
                seen.add(er.key());
                result.add(er);
            }
        }

        // Replaced baselines are hidden by default; surface them only when opted in.
        if (query.includeReplaced()) {
            for (ResourceLocation targetId : snap.replacedTargets()) {
                SRERole raw = lookup.find(targetId);
                if (raw == null || raw.identifier() == null) {
                    continue;
                }
                EffectiveRole baseline = new EffectiveRole(RoleKey.of(targetId), raw,
                        EffectiveRole.Source.BASELINE);
                if (seen.add(baseline.key()) && matches(baseline, query)) {
                    result.add(baseline);
                }
            }
        }

        if (query.ordering() != RoleOrdering.REGISTRATION) {
            result.sort(query.ordering().comparator());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * The effective-directory snapshot this catalog answers from: the manager's
     * frozen lobby/round/settlement snapshot when present, otherwise a
     * lazily-compiled temp view ({@link RoleSnapshotVersions#TEMP}) built by the
     * single authoritative {@link RoleSnapshotCompiler} from the injected raw
     * source (unit tests, pre-{@code SERVER_STARTED}). Temp compiles are not
     * archived and do not consume a published generation.
     *
     * <p><b>审核 R-22</b>：旧实现每次读都全量重编译 TEMP——而客户端
     * {@code lobby} 快照永不被设置（只有服务端 {@code setLobby}），教程又鼓励把所有查询
     * 都走目录，于是 HUD / 每帧查询变成一个性能陷阱。现在按「原始角色来源指纹 +
     * 覆盖引擎快照版本 + 角色扩展配置修订号」缓存 TEMP，三者任一变化才重编译。
     * 该指纹是廉价 O(n) 扫描（不构建 EffectiveRole），因此每帧查询仍有成本，
     * 但不再触发完整编译。</p>
     */
    private RoleSnapshot compiledSnapshot() {
        RoleSnapshot frozen = RoleSnapshotManager.INSTANCE.current();
        if (frozen != null) {
            return frozen;
        }
        long version = tempVersion();
        RoleSnapshot cached = cachedTempSnapshot;
        if (cached != null && cachedTempVersion == version) {
            return cached;
        }
        RoleSnapshot compiled = RoleSnapshotCompiler.compile(RoleSnapshotVersions.TEMP, lookup);
        cachedTempSnapshot = compiled;
        cachedTempVersion = version;
        return compiled;
    }

    /** 审核 R-22：TEMP 编译缓存的失效判据。 */
    private long tempVersion() {
        long h = 17L;
        int count = 0;
        for (SRERole role : lookup.all()) {
            count++;
            // 原始角色对象被替换（REPLACE / 直接注册）时 identityHashCode 会变。
            h = h * 31L + (role == null ? 0 : System.identityHashCode(role));
        }
        h = h * 31L + count;
        // MODIFY / ALIAS / 配置门控变化分别经这两条路径体现。
        h = h * 31L + engine().getSnapshotVersion();
        h = h * 31L + RoleExtensionConfigService.INSTANCE.revision();
        return h;
    }

    private static boolean matches(EffectiveRole er, RoleQuery query) {
        SRERole role = er.role();
        if (role == null || role.identifier() == null) {
            return false;
        }

        // QuerySide: no side-scoped roles exist yet, so every side matches.
        // (Reserved for a later increment that introduces side-specific roles.)

        if (query.purpose() == QueryPurpose.RANDOM && !role.canBeRandomedDefination()) {
            return false;
        }
        if (query.excludesOtherModeRoles() && role.isOtherModeRole()) {
            return false;
        }
        if (!query.mapAbilities().isEmpty()
                && role.getSpecialMapRole() != SRERole.SpecialMapRoleMap.ALL
                && !query.mapAbilities().contains(role.getSpecialMapRole())) {
            return false;
        }
        if (!query.factions().isEmpty()
                && query.factions().stream().noneMatch(f -> f.matches(role))) {
            return false;
        }
        if (query.providerNamespace() != null
                && !query.providerNamespace().equals(er.key().namespace())) {
            return false;
        }
        if (!query.tags().isEmpty() && !role.isFlag(query.tags().toArray(new String[0]))) {
            return false;
        }
        int playerCount = query.playerCount();
        if (playerCount >= 0) {
            if (role.defaultEnableNeedPlayerCount > 0
                    && playerCount < role.defaultEnableNeedPlayerCount) {
                return false;
            }
            if (role.defaultEnableMaxPlayerCount > 0
                    && playerCount > role.defaultEnableMaxPlayerCount) {
                return false;
            }
        }
        return true;
    }

    @Override
    public RoleKey canonicalize(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        RoleSnapshot frozen = RoleSnapshotManager.INSTANCE.current();
        if (frozen != null) {
            return frozen.canonicalize(id);
        }
        // v2 ALIAS redirect.
        ResourceLocation alias = RoleExtensionRegistry.INSTANCE.resolveAlias(id);
        if (alias != null) {
            return RoleKey.of(alias);
        }
        // v2 REPLACE redirect (config-enabled replacements only).
        RoleReplacement repl = RoleExtensionRegistry.INSTANCE.replacementFor(id);
        if (repl != null && RoleExtensionRegistry.INSTANCE.isReplacementActive(id)) {
            if (repl.identity() == ReplacementIdentity.KEEP_CANONICAL_ID
                    || repl.identity() == ReplacementIdentity.PRESERVE_TARGET_ID) {
                return RoleKey.of(id);
            }
            return RoleKey.of(repl.replacement().key().location());
        }
        // Legacy (v1) replacement redirect. Pure lookups only: canonicalize()
        // must never materialize a MODIFY overlay onto a live role — that is the
        // runtime applier's job at snapshot activation. Resolving through
        // SreRoleOverrideResolver.resolve() here would mutate the raw role as a
        // side effect (applyModifies), making canonicalization impure and
        // inconsistent with the snapshot compiler.
        RoleOverrideEngine engine = engine();
        SRERole directReplacement = engine.getReplacement(id);
        if (directReplacement != null && directReplacement.identifier() != null) {
            return RoleKey.of(directReplacement.identifier());
        }
        ResourceLocation targetId = engine.getManagedTargetId(id);
        if (targetId != null) {
            SRERole activeForTarget = engine.getReplacement(targetId);
            if (activeForTarget != null && activeForTarget.identifier() != null) {
                return RoleKey.of(activeForTarget.identifier());
            }
            return RoleKey.of(targetId);
        }
        return RoleKey.of(id);
    }

    @Override
    public Optional<EffectiveRole> find(RoleKey keyOrAlias) {
        RoleKey canonical = canonicalize(keyOrAlias.location());
        for (EffectiveRole er : effectiveRoles()) {
            if (er.key().equals(canonical)) {
                return Optional.of(er);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<EffectiveRole> resolve(SRERole rawRole) {
        if (rawRole == null || rawRole.identifier() == null) {
            return Optional.empty();
        }
        RoleKey canonical = canonicalize(rawRole.identifier());
        return find(canonical);
    }

    @Override
    public Optional<EffectiveRole> resolveStored(String storedValue) {
        RoleKey parsed = RoleKey.tryParse(storedValue);
        if (parsed != null) {
            Optional<EffectiveRole> viaCanonical = find(canonicalize(parsed.location()));
            if (viaCanonical.isPresent()) {
                return viaCanonical;
            }
        }
        SRERole resolved = SreRoleOverrideResolver.resolveStoredId(storedValue);
        if (resolved == null || resolved.identifier() == null) {
            return Optional.empty();
        }
        return find(RoleKey.of(resolved.identifier()));
    }

    @Override
    public boolean isActive(RoleKey key) {
        return find(key).isPresent();
    }

    @Override
    public boolean isReplaced(RoleKey key) {
        RoleSnapshot frozen = RoleSnapshotManager.INSTANCE.current();
        if (frozen != null) {
            return frozen.isReplaced(key.location());
        }
        return RoleExtensionRegistry.INSTANCE.isActiveReplaced(key.location())
                || engine().isReplaced(key.location());
    }

    @Override
    public boolean isModified(RoleKey key) {
        RoleSnapshot frozen = RoleSnapshotManager.INSTANCE.current();
        if (frozen != null) {
            return frozen.find(key).map(er -> er.source() == EffectiveRole.Source.MODIFIED).orElse(false);
        }
        return RoleExtensionRegistry.INSTANCE.isModified(key.location())
                || engine().isModified(key.location());
    }

    @Override
    public boolean isAdded(RoleKey key) {
        return RoleExtensionRegistry.INSTANCE.isAdded(key.location());
    }

    @Override
    public RoleSnapshotId snapshot() {
        RoleSnapshot current = RoleSnapshotManager.INSTANCE.current();
        if (current != null) {
            return current.id();
        }
        return RoleSnapshotVersions.TEMP;
    }

    @Override
    public Optional<RoleSnapshot> currentSnapshot() {
        return Optional.ofNullable(RoleSnapshotManager.INSTANCE.current());
    }

    @Override
    public Optional<RoleSnapshot> roundSnapshot() {
        return Optional.ofNullable(RoleSnapshotManager.INSTANCE.round());
    }

    @Override
    public Optional<RoleSnapshot> lastEndedSnapshot() {
        return Optional.ofNullable(RoleSnapshotManager.INSTANCE.lastEnded());
    }

    @Override
    public Optional<EffectiveRole> restore(RoleSnapshotId snapshot, RoleKey key) {
        return RoleSnapshotArchive.INSTANCE.restore(snapshot, key);
    }
}
