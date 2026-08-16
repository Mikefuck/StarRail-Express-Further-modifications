package com.habitrain.core.api.role.v2.state;

import com.habitrain.core.api.role.v2.RoleKey;
import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Schema for one namespaced role-state slot.
 *
 * <p>Providers register a spec (not a CCA key). The platform stores values
 * under {@code provider/role/state-key}. Serialization is the Codec contract:
 * a spec that declares {@link Persistence#WORLD}/{@link Persistence#PERMANENT}
 * or a non-{@link SyncPolicy#NONE} sync policy MUST provide a {@link Codec},
 * otherwise registration fails with INVALID (fix-doc §10.6 — no declared
 * persistence/sync is silently ignored).
 *
 * @param <T> value type. Prefer boxed primitives and immutable records.
 */
public final class RoleStateSpec<T> {

    private final ResourceLocation id;
    private final RoleKey role;
    private final Class<T> type;
    private final StateScope scope;
    private final Persistence persistence;
    private final SyncPolicy sync;
    private final Set<ResetCause> resetOn;
    private final Supplier<T> defaultValue;
    private final int dataVersion;
    private final int maxSerializedBytes;
    private final @Nullable Codec<T> codec;
    /** Continuous migration chain; index i migrates version {@code (i+1) -> (i+2)}. */
    private final List<UnaryOperator<T>> migrations;

    private RoleStateSpec(Builder<T> b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.role = Objects.requireNonNull(b.role, "role");
        this.type = Objects.requireNonNull(b.type, "type");
        this.scope = b.scope;
        this.persistence = b.persistence;
        this.sync = b.sync;
        this.resetOn = Set.copyOf(b.resetOn);
        this.defaultValue = b.defaultValue;
        this.dataVersion = b.dataVersion;
        this.maxSerializedBytes = b.maxSerializedBytes;
        this.codec = b.codec;
        this.migrations = List.copyOf(b.migrations);
    }

    public static <T> Builder<T> of(String namespace, String path, Class<T> type) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        return new Builder<T>().id(ResourceLocation.fromNamespaceAndPath(namespace, path)).type(type);
    }

    public static <T> Builder<T> of(ResourceLocation id, Class<T> type) {
        return new Builder<T>().id(id).type(type);
    }

    public ResourceLocation id() { return id; }
    public RoleKey role() { return role; }
    public Class<T> type() { return type; }
    public StateScope scope() { return scope; }
    public Persistence persistence() { return persistence; }
    public SyncPolicy sync() { return sync; }
    public Set<ResetCause> resetOn() { return resetOn; }
    public Supplier<T> defaultValue() { return defaultValue; }
    public int dataVersion() { return dataVersion; }
    public int maxSerializedBytes() { return maxSerializedBytes; }
    /** The serialization contract, or {@code null} when persistence/sync never leave memory. */
    public @Nullable Codec<T> codec() { return codec; }
    /** The registered migration chain (index i migrates version {@code i+1 -> i+2}). */
    public List<UnaryOperator<T>> migrations() { return migrations; }

    /** Instantiates the declared default, or {@code null} when none was set. */
    public @Nullable T produceDefault() {
        return defaultValue == null ? null : defaultValue.get();
    }

    /**
     * Migrates {@code value} from {@code storedVersion} to the current
     * {@link #dataVersion}. Returns {@code null} when the migration chain is
     * insufficient (caller treats this as {@code DATA_MIGRATION_REQUIRED} and
     * must keep the original stored bytes untouched).
     */
    public @Nullable T migrateValue(int storedVersion, T value) {
        if (storedVersion >= dataVersion) {
            return value;
        }
        if (storedVersion < 1 || value == null) {
            return null;
        }
        int needed = dataVersion - storedVersion;
        if (migrations.size() < needed) {
            return null;
        }
        T v = value;
        for (int i = storedVersion - 1; i < storedVersion - 1 + needed; i++) {
            v = migrations.get(i).apply(v);
            if (v == null) {
                return null;
            }
        }
        return v;
    }

    public static final class Builder<T> {
        private ResourceLocation id;
        private RoleKey role;
        private Class<T> type;
        private StateScope scope = StateScope.PLAYER;
        private Persistence persistence = Persistence.ROUND;
        private SyncPolicy sync = SyncPolicy.NONE;
        private final EnumSet<ResetCause> resetOn = EnumSet.noneOf(ResetCause.class);
        private Supplier<T> defaultValue;
        private int dataVersion = 1;
        private int maxSerializedBytes = 256;
        private @Nullable Codec<T> codec;
        private final List<UnaryOperator<T>> migrations = new ArrayList<>();

        private Builder() {}

        public Builder<T> id(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        public Builder<T> role(RoleKey role) {
            this.role = Objects.requireNonNull(role, "role");
            return this;
        }

        public Builder<T> type(Class<T> type) {
            this.type = Objects.requireNonNull(type, "type");
            return this;
        }

        public Builder<T> scope(StateScope scope) {
            this.scope = Objects.requireNonNull(scope, "scope");
            return this;
        }

        public Builder<T> persistence(Persistence persistence) {
            this.persistence = Objects.requireNonNull(persistence, "persistence");
            return this;
        }

        public Builder<T> sync(SyncPolicy sync) {
            this.sync = Objects.requireNonNull(sync, "sync");
            return this;
        }

        /**
         * Sets the serialization contract. Required when the spec declares
         * {@link Persistence#WORLD}/{@link Persistence#PERMANENT} or any
         * non-{@link SyncPolicy#NONE} sync policy.
         */
        public Builder<T> codec(Codec<T> codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Appends one migration step {@code fromVersion -> fromVersion + 1}.
         * Steps must form a continuous chain starting at v1 and reaching
         * {@link #dataVersion(int)} (e.g. dataVersion=3 needs 1→2 and 2→3).
         * Call {@code dataVersion(...)} before {@code migrate(...)}.
         */
        public Builder<T> migrate(int fromVersion, UnaryOperator<T> fn) {
            Objects.requireNonNull(fn, "migration");
            int expected = migrations.size() + 1;
            if (fromVersion != expected) {
                throw new IllegalArgumentException(
                        "migration chain must be continuous starting at v1: expected fromVersion=" + expected
                                + " for dataVersion=" + dataVersion + " but got " + fromVersion);
            }
            migrations.add(fn);
            return this;
        }

        public Builder<T> resetOn(ResetCause... causes) {
            Objects.requireNonNull(causes, "causes");
            for (ResetCause cause : causes) {
                this.resetOn.add(Objects.requireNonNull(cause, "cause"));
            }
            return this;
        }

        public Builder<T> defaultValue(Supplier<T> defaultValue) {
            this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
            return this;
        }

        public Builder<T> dataVersion(int dataVersion) {
            if (dataVersion < 1) {
                throw new IllegalArgumentException("dataVersion must be >= 1");
            }
            this.dataVersion = dataVersion;
            return this;
        }

        public Builder<T> maxSerializedBytes(int maxSerializedBytes) {
            if (maxSerializedBytes < 0) {
                throw new IllegalArgumentException("maxSerializedBytes must be >= 0");
            }
            this.maxSerializedBytes = maxSerializedBytes;
            return this;
        }

        public RoleStateSpec<T> build() {
            if (id == null) {
                throw new IllegalStateException("RoleStateSpec requires an id");
            }
            if (role == null) {
                throw new IllegalStateException("RoleStateSpec requires a role");
            }
            if (type == null) {
                throw new IllegalStateException("RoleStateSpec requires a type");
            }
            if (resetOn.isEmpty()) {
                // PLAYER slots drop when the holder loses the role. ROUND
                // persistence / ROUND scope drop at round end. WORLD/PERMANENT
                // without an explicit cause stay until MANUAL.
                if (scope == StateScope.PLAYER) {
                    resetOn.add(ResetCause.ROLE_LOST);
                }
                if (scope == StateScope.ROUND
                        || persistence == Persistence.ROUND
                        || persistence == Persistence.NONE) {
                    resetOn.add(ResetCause.ROUND_END);
                }
            }
            if (!migrations.isEmpty() && migrations.size() != dataVersion - 1) {
                throw new IllegalStateException(
                        "migration chain must reach the declared dataVersion " + dataVersion
                                + " (chain has " + migrations.size() + " steps)");
            }
            boolean needsCodec = persistence == Persistence.WORLD
                    || persistence == Persistence.PERMANENT
                    || sync != SyncPolicy.NONE;
            if (needsCodec && codec == null) {
                throw new IllegalStateException(
                        "RoleStateSpec " + id + " requires a codec because persistence=" + persistence
                                + " or sync=" + sync + "; refusing to silently ignore the declaration");
            }
            return new RoleStateSpec<>(this);
        }
    }
}
