package com.habitrain.core.api.role.v2;

import io.wifi.starrailexpress.api.GameMode;
import io.wifi.starrailexpress.api.SRERole;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable query over the effective role catalog ({@link RoleCatalogApi#effectiveRoles(RoleQuery)}).
 *
 * <p>Every filter is additive and optional. An unset dimension matches
 * everything; an empty {@code Set} (e.g. {@code mapAbilities}, {@code factions},
 * {@code tags}) means "no restriction". Filters are ANDed together; a faction
 * selection is ORed within itself (a role matches when it satisfies any of the
 * selected factions).
 *
 * <p><b>Reserved / partial dimensions:</b> {@link QuerySide} currently resolves
 * to the same shared role set for every value (no side-scoped roles exist yet);
 * {@link #mode} participates in the "other-mode role" exclusion described below
 * ({@link QueryPurpose#LOTTERY_CARD}/{@link QueryPurpose#SELF_SELECT} exclude
 * even without a mode).
 * These are part of the contract for forward compatibility and are documented
 * as such rather than silently doing nothing.
 */
public final class RoleQuery {

    private final QueryPurpose purpose;
    private final QuerySide side;
    private final @Nullable GameMode mode;
    private final Set<SRERole.SpecialMapRoleMap> mapAbilities;
    private final Set<RoleFaction> factions;
    private final boolean includeReplaced;
    private final boolean includeDisabled;
    private final boolean includeInvalid;
    private final @Nullable String providerNamespace;
    private final Set<String> tags;
    private final int playerCount;
    private final RoleOrdering ordering;

    private RoleQuery(Builder b) {
        this.purpose = b.purpose;
        this.side = b.side;
        this.mode = b.mode;
        this.mapAbilities = Collections.unmodifiableSet(new LinkedHashSet<>(b.mapAbilities));
        this.factions = Collections.unmodifiableSet(new LinkedHashSet<>(b.factions));
        this.includeReplaced = b.includeReplaced;
        this.includeDisabled = b.includeDisabled;
        this.includeInvalid = b.includeInvalid;
        this.providerNamespace = b.providerNamespace;
        this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(b.tags));
        this.playerCount = b.playerCount;
        this.ordering = b.ordering;
    }

    /** A query with no restrictions over the full effective role set. */
    public static RoleQuery generic() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public QueryPurpose purpose() { return purpose; }
    public QuerySide side() { return side; }
    public @Nullable GameMode mode() { return mode; }
    public Set<SRERole.SpecialMapRoleMap> mapAbilities() { return mapAbilities; }
    public Set<RoleFaction> factions() { return factions; }
    public boolean includeReplaced() { return includeReplaced; }
    /**
     * Experimental compatibility flag. The effective-role catalog deliberately
     * contains only runnable roles, so this flag cannot surface disabled
     * declaration rows. Use {@link RoleDiagnostics#entries()} for those rows.
     *
     * @deprecated This flag has no catalog filtering semantics. Inspect
     *             {@link RoleDiagnostics} instead.
     */
    @Deprecated(since = "2.0", forRemoval = false)
    public boolean includeDisabled() { return includeDisabled; }
    /**
     * Experimental compatibility flag. Invalid declarations never create an
     * {@link EffectiveRole}; use {@link RoleDiagnostics#entries()} to inspect
     * them.
     *
     * @deprecated This flag has no catalog filtering semantics. Inspect
     *             {@link RoleDiagnostics} instead.
     */
    @Deprecated(since = "2.0", forRemoval = false)
    public boolean includeInvalid() { return includeInvalid; }
    public @Nullable String providerNamespace() { return providerNamespace; }
    public Set<String> tags() { return tags; }
    public int playerCount() { return playerCount; }
    public RoleOrdering ordering() { return ordering; }

    /**
     * Whether other-mode roles should be excluded.
     *
     * <p>{@link QueryPurpose#LOTTERY_CARD} and {@link QueryPurpose#SELF_SELECT}
     * always exclude them so lottery/self-select pools are usable without a
     * {@link GameMode}. Other excluding purposes still require {@link #mode()}
     * to be set. {@link QueryPurpose#GENERIC} and {@link QueryPurpose#ROTATION}
     * never exclude.
     */
    public boolean excludesOtherModeRoles() {
        if (purpose.excludesOtherModeRolesWithoutMode()) {
            return true;
        }
        return mode != null && purpose.excludesOtherModeRoles();
    }

    @Override
    public String toString() {
        return "RoleQuery{purpose=" + purpose + ", side=" + side
                + ", mode=" + (mode == null ? "any" : mode.identifier)
                + ", mapAbilities=" + mapAbilities + ", factions=" + factions
                + ", includeReplaced=" + includeReplaced
                + ", includeDisabled=" + includeDisabled
                + ", includeInvalid=" + includeInvalid
                + ", provider=" + providerNamespace + ", tags=" + tags
                + ", playerCount=" + playerCount + ", ordering=" + ordering + "}";
    }

    public static final class Builder {
        private QueryPurpose purpose = QueryPurpose.GENERIC;
        private QuerySide side = QuerySide.LOGICAL;
        private @Nullable GameMode mode;
        private final Set<SRERole.SpecialMapRoleMap> mapAbilities = new LinkedHashSet<>();
        private final Set<RoleFaction> factions = new LinkedHashSet<>();
        private boolean includeReplaced;
        private boolean includeDisabled;
        private boolean includeInvalid;
        private @Nullable String providerNamespace;
        private final Set<String> tags = new LinkedHashSet<>();
        private int playerCount = -1;
        private RoleOrdering ordering = RoleOrdering.REGISTRATION;

        public Builder purpose(QueryPurpose purpose) {
            this.purpose = Objects.requireNonNull(purpose, "purpose");
            return this;
        }

        public Builder side(QuerySide side) {
            this.side = Objects.requireNonNull(side, "side");
            return this;
        }

        public Builder mode(@Nullable GameMode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * 地图能力过滤（审核 A-08）。
         *
         * <p>{@code null} 数组或数组内含 {@code null} 元素过去会直接
         * {@link Collections#addAll} → 写入 {@code null} 元素（或抛 NPE），
         * 之后在过滤阶段以一个难以定位的 NPE 爆炸。现在显式校验并跳过 {@code null} 元素，
         * 与 {@link #factions(RoleFaction...)} / {@link #tags(String...)} 策略一致。
         */
        public Builder mapAbilities(SRERole.SpecialMapRoleMap... abilities) {
            if (abilities == null) {
                throw new NullPointerException("mapAbilities must not be null (audit A-08)");
            }
            for (SRERole.SpecialMapRoleMap ability : abilities) {
                if (ability != null) {
                    this.mapAbilities.add(ability);
                }
            }
            return this;
        }

        /**
         * 阵营过滤（审核 A-08）。
         *
         * <p>旧实现 {@code Collections.addAll(this.factions, factions)} 在
         * {@code factions == null} 时抛 NPE（{@code Collections.addAll} 对 null 数组的行为），
         * 而 javadoc 从未声明该约束；数组里有 {@code null} 元素则会把 {@code null} 塞进集合。
         * 现在：{@code null} 数组显式抛带参数名的 NPE，{@code null} 元素静默跳过
         * （调用方常见写法是 {@code factions(a, maybeNull, b)}）。
         */
        public Builder factions(RoleFaction... factions) {
            if (factions == null) {
                throw new NullPointerException("factions must not be null (audit A-08)");
            }
            for (RoleFaction faction : factions) {
                if (faction != null) {
                    this.factions.add(faction);
                }
            }
            return this;
        }

        public Builder includeReplaced(boolean includeReplaced) {
            this.includeReplaced = includeReplaced;
            return this;
        }

        /**
         * Retained for source compatibility only; disabled declarations are
         * available from {@link RoleDiagnostics}, not an effective-role query.
         *
         * @deprecated Use {@link RoleDiagnostics#entries()}.
         */
        @Deprecated(since = "2.0", forRemoval = false)
        public Builder includeDisabled(boolean includeDisabled) {
            this.includeDisabled = includeDisabled;
            return this;
        }

        /**
         * Retained for source compatibility only; invalid declarations are
         * available from {@link RoleDiagnostics}, not an effective-role query.
         *
         * @deprecated Use {@link RoleDiagnostics#entries()}.
         */
        @Deprecated(since = "2.0", forRemoval = false)
        public Builder includeInvalid(boolean includeInvalid) {
            this.includeInvalid = includeInvalid;
            return this;
        }

        public Builder provider(String providerNamespace) {
            this.providerNamespace = providerNamespace == null
                    ? null
                    : providerNamespace.trim().toLowerCase(Locale.ROOT);
            return this;
        }

        /**
         * 标签过滤（审核 A-08：与 {@code factions} / {@code mapAbilities} 同策略——
         * {@code null} 数组显式抛 NPE，{@code null} 元素跳过）。
         */
        public Builder tags(String... tags) {
            if (tags == null) {
                throw new NullPointerException("tags must not be null (audit A-08)");
            }
            for (String tag : tags) {
                if (tag != null) {
                    this.tags.add(tag);
                }
            }
            return this;
        }

        /**
         * The current player count to evaluate spawn applicability against. Roles
         * whose {@code defaultEnableNeedPlayerCount}/{@code defaultEnableMaxPlayerCount}
         * window does not contain this count are excluded. Negative disables the filter.
         */
        public Builder playerCount(int playerCount) {
            this.playerCount = playerCount;
            return this;
        }

        public Builder ordering(RoleOrdering ordering) {
            this.ordering = Objects.requireNonNull(ordering, "ordering");
            return this;
        }

        public RoleQuery build() {
            return new RoleQuery(this);
        }
    }
}
