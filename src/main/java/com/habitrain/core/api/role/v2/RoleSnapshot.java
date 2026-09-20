package com.habitrain.core.api.role.v2;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable, frozen capture of the effective role catalog at a point in time.
 *
 * <p>A {@code RoleSnapshot} is compiled once (lobby) and fixed again at round
 * start, so an in-progress round is never disturbed by mid-round config changes.
 * It holds the effective roles keyed by canonical id, the alias redirects and the
 * set of replaced (hidden) targets. Consumers read from a snapshot instead of the
 * live registry to get a stable answer.
 */
public final class RoleSnapshot {

    private final RoleSnapshotId id;
    private final Map<ResourceLocation, EffectiveRole> roles;
    private final Map<ResourceLocation, ResourceLocation> aliases;
    private final Set<ResourceLocation> replacedTargets;
    private final Set<BehaviorEntry> enabledBehaviorEntries;
    private final boolean allowGlobalHooks;

    public RoleSnapshot(RoleSnapshotId id,
                        Map<ResourceLocation, EffectiveRole> roles,
                        Map<ResourceLocation, ResourceLocation> aliases,
                        Set<ResourceLocation> replacedTargets) {
        this(id, roles, aliases, replacedTargets, Set.of(), false);
    }

    /**
     * Full snapshot constructor.  Behavior gates are compiled alongside role
     * data so dispatch does not consult a mutable config during a round.
     *
     * <p><b>审核 A-12</b>：旧实现只对 {@code id} 判空，{@code roles} / {@code aliases} /
     * {@code replacedTargets} 直接交给 {@code new LinkedHashMap<>(...)} / {@code new LinkedHashSet<>(...)}——
     * 传入 {@code null} 会在快照构造点抛一个<b>没有参数名</b>的 NPE（{@code LinkedHashMap}
     * 的构造器 NPE 消息为空），定位成本很高。现在四个字段统一 {@link Objects#requireNonNull}，
     * 消息里带字段名。
     */
    public RoleSnapshot(RoleSnapshotId id,
                        Map<ResourceLocation, EffectiveRole> roles,
                        Map<ResourceLocation, ResourceLocation> aliases,
                        Set<ResourceLocation> replacedTargets,
                        Set<BehaviorEntry> enabledBehaviorEntries,
                        boolean allowGlobalHooks) {
        this.id = Objects.requireNonNull(id, "id");
        this.roles = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(roles, "roles")));
        this.aliases = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(aliases, "aliases")));
        this.replacedTargets = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(replacedTargets, "replacedTargets")));
        this.enabledBehaviorEntries = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNullElse(enabledBehaviorEntries, Set.of())));
        this.allowGlobalHooks = allowGlobalHooks;
    }

    public RoleSnapshotId id() {
        return id;
    }

    /** The effective roles keyed by canonical id. */
    public Map<ResourceLocation, EffectiveRole> roles() {
        return roles;
    }

    /** The alias redirects (old id -> canonical id). */
    public Map<ResourceLocation, ResourceLocation> aliases() {
        return aliases;
    }

    /** The replaced (hidden) target ids. */
    public Set<ResourceLocation> replacedTargets() {
        return replacedTargets;
    }

    /** The full effective role set. */
    public Collection<EffectiveRole> effectiveRoles() {
        return roles.values();
    }

    /**
     * Finds an effective role by key (following aliases and replacements).
     *
     * <p><b>审核 A-12</b>：javadoc 过去只说「finds」，实现却在 {@code keyOrAlias == null}
     * 时于 {@code keyOrAlias.location()} 抛 NPE——文档强于实现。现在明确：
     * {@code null} 视为「查不到」，返回 {@link Optional#empty()}。
     */
    public Optional<EffectiveRole> find(@Nullable RoleKey keyOrAlias) {
        if (keyOrAlias == null || keyOrAlias.location() == null) {
            return Optional.empty();
        }
        RoleKey canonical = canonicalize(keyOrAlias.location());
        EffectiveRole er = roles.get(canonical.location());
        return er == null ? Optional.empty() : Optional.of(er);
    }

    /**
     * Maps an id to its canonical key, following aliases and replacements.
     *
     * <p><b>审核 A-12</b>：{@code id == null} 时旧实现返回
     * {@code RoleKey.of(null)}——把 NPE 推给下游更远的位置。现在显式拒绝。
     *
     * @throws NullPointerException {@code id} 为 {@code null}
     */
    public RoleKey canonicalize(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        ResourceLocation cur = id;
        java.util.Set<ResourceLocation> seen = new java.util.HashSet<>();
        while (cur != null && aliases.containsKey(cur)) {
            if (!seen.add(cur)) {
                break;
            }
            cur = aliases.get(cur);
        }
        return RoleKey.of(cur == null ? id : cur);
    }

    /**
     * Whether the id is a replaced (hidden) target.
     * {@code null} 视为「不是」（审核 A-17：可空入参显式声明）。
     */
    public boolean isReplaced(@Nullable ResourceLocation id) {
        return id != null && replacedTargets.contains(id);
    }

    /**
     * Whether the id has an effective role in this snapshot.
     * {@code null} 视为「没有」（审核 A-17）。
     */
    public boolean isActive(@Nullable ResourceLocation id) {
        return id != null && roles.containsKey(id);
    }

    /** The effective role for a canonical id, or {@code null}（{@code null} 入参也返回 {@code null}）。 */
    public @Nullable EffectiveRole get(@Nullable ResourceLocation canonicalId) {
        return canonicalId == null ? null : roles.get(canonicalId);
    }

    /** Whether a provider-owned behavior entry was enabled at compile time. */
    public boolean isBehaviorEntryEnabled(String providerId, String entryId) {
        return enabledBehaviorEntries.contains(new BehaviorEntry(providerId, entryId));
    }

    /** The provider/entry pairs that were enabled when this snapshot was compiled. */
    public Set<BehaviorEntry> enabledBehaviorEntries() {
        return enabledBehaviorEntries;
    }

    /** The immutable gate for {@code GLOBAL_WHILE_ENABLED} hooks. */
    public boolean allowGlobalHooks() {
        return allowGlobalHooks;
    }

    /** Provider/entry identity captured for a behavior declaration. */
    public record BehaviorEntry(String providerId, String entryId) {
        public BehaviorEntry {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(entryId, "entryId");
        }
    }

    /**
     * Produces a historical-safe copy retaining only immutable profiles.  The
     * live manager keeps runtime handles in its own snapshot; the bounded archive
     * must never retain a mutable {@code SRERole} reference.
     */
    public RoleSnapshot withoutRuntimeHandles() {
        Map<ResourceLocation, EffectiveRole> historical = new LinkedHashMap<>();
        for (var entry : roles.entrySet()) {
            historical.put(entry.getKey(), entry.getValue().withoutRuntimeHandle());
        }
        return new RoleSnapshot(id, historical, aliases, replacedTargets,
                enabledBehaviorEntries, allowGlobalHooks);
    }
}
