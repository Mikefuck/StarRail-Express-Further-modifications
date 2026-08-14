package com.habitrain.core.role.state;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.state.Persistence;
import com.habitrain.core.api.role.v2.state.ResetCause;
import com.habitrain.core.api.role.v2.state.RoleStateApi;
import com.habitrain.core.api.role.v2.state.RoleStateKey;
import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import com.habitrain.core.api.role.v2.state.StateScope;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Predicate;

/**
 * {@link RoleStateApi} implementation with a real storage contract
 * (fix-doc §10).
 *
 * <p>Transient slots ({@link Persistence#NONE}/{@link Persistence#ROUND})
 * live in a process map. Persistent slots ({@link Persistence#WORLD}/
 * {@link Persistence#PERMANENT}) are codec-encoded and stored in an
 * injectable {@link RoleStateStore} — the CCA containers in production, an
 * in-memory map in tests. Reads migrate stored data up to the spec's current
 * {@code dataVersion}; an incomplete migration chain leaves the original
 * bytes untouched and flags the slot as {@code DATA_MIGRATION_REQUIRED}
 * (fix-doc §10.5). {@link StateScope#WORLD}/{@link StateScope#ROUND} slots are
 * keyed by world key, so two worlds never share a static map (§10.2).
 */
public final class RoleStateServiceImpl implements RoleStateApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleStateApi");

    private final Map<StorageKey, RoleStateSpec<?>> specs = new LinkedHashMap<>();
    private final Map<StateSlotKey, Object> transientValues = new ConcurrentHashMap<>();
    private final Set<StateSlotKey> migrationRequired = new CopyOnWriteArraySet<>();
    private final RoleStateSyncService syncService = new RoleStateSyncService();
    private volatile RoleStateStore store = new MemoryRoleStateStore();
    private volatile boolean frozen;

    public RoleStateServiceImpl() {}

    /** Binds the persistence backend (production = CCA store; tests inject memory/fakes). */
    public void setStore(RoleStateStore store) {
        this.store = store == null ? new MemoryRoleStateStore() : store;
    }

    /** The sync service used after writes; tests configure its recipients/sender. */
    public RoleStateSyncService syncService() {
        return syncService;
    }

    @Override
    public synchronized <T> RoleStateKey<T> register(RoleStateSpec<T> spec) {
        Objects.requireNonNull(spec, "spec");
        if (frozen) {
            throw new IllegalStateException("Role state registry is frozen");
        }
        StorageKey storage = StorageKey.of(spec);
        if (specs.containsKey(storage)) {
            throw new IllegalArgumentException("Duplicate role state: " + storage);
        }
        specs.put(storage, spec);
        LOGGER.info("Registered role state {}", storage);
        return new RoleStateKey<>(spec.id(), spec.role(), spec.type());
    }

    /** Snapshot/restore seam for a provider registration transaction. */
    public synchronized RegistrationSnapshot snapshotForTransaction() {
        return new RegistrationSnapshot(List.copyOf(specs.values()), frozen);
    }

    /** Restores schema declarations without touching persisted player values. */
    public synchronized void restoreTransactionSnapshot(RegistrationSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        specs.clear();
        for (RoleStateSpec<?> spec : snapshot.specs()) {
            specs.put(StorageKey.of(spec), spec);
        }
        frozen = snapshot.frozen();
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T> RoleStateSpec<T> spec(RoleStateKey<T> key) {
        if (key == null) {
            return null;
        }
        return (RoleStateSpec<T>) specs.get(StorageKey.of(key));
    }

    @Override
    public Collection<RoleStateSpec<?>> specs() {
        return Collections.unmodifiableCollection(specs.values());
    }

    @Override
    public List<RoleStateSpec<?>> specsFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        List<RoleStateSpec<?>> out = new ArrayList<>();
        for (RoleStateSpec<?> spec : specs.values()) {
            if (role.equals(spec.role())) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public @Nullable <T> T get(RoleStateKey<T> key, @Nullable ServerPlayer player) {
        return get(key, player == null ? null : player.getUUID(), worldKeyOf(player));
    }

    @Override
    public @Nullable <T> T get(RoleStateKey<T> key, @Nullable UUID playerId) {
        return get(key, playerId, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T> T get(RoleStateKey<T> key, @Nullable UUID playerId,
                               @Nullable ResourceLocation worldKey) {
        RoleStateSpec<T> spec = spec(key);
        if (spec == null) {
            return null;
        }
        if (spec.scope() == StateScope.PLAYER && playerId == null) {
            return spec.produceDefault();
        }
        StateSlotKey slot = StateSlotKey.of(spec, playerId, worldKey);
        if (isTransient(spec)) {
            Object stored = transientValues.get(slot);
            if (stored == NULL) {
                return null;
            }
            if (stored != null) {
                return (T) stored;
            }
            return spec.produceDefault();
        }
        StoredState stored = store.read(slot);
        if (stored == null) {
            return spec.produceDefault();
        }
        if (stored.isNull()) {
            return null;
        }
        return tryDecode(spec, slot, stored);
    }

    @Override
    public <T> void set(RoleStateKey<T> key, @Nullable ServerPlayer player, @Nullable T value) {
        set(key, player == null ? null : player.getUUID(), worldKeyOf(player), value);
    }

    @Override
    public <T> void set(RoleStateKey<T> key, @Nullable UUID playerId, @Nullable T value) {
        set(key, playerId, null, value);
    }

    @Override
    public <T> void set(RoleStateKey<T> key, @Nullable UUID playerId,
                        @Nullable ResourceLocation worldKey, @Nullable T value) {
        RoleStateSpec<T> spec = spec(key);
        if (spec == null) {
            throw new IllegalArgumentException("Unregistered role state: " + key);
        }
        if (value != null && !spec.type().isInstance(value)) {
            throw new IllegalArgumentException(
                    "Value " + value.getClass().getName() + " is not a " + spec.type().getName());
        }
        if (spec.scope() == StateScope.PLAYER && playerId == null) {
            throw new IllegalArgumentException("PLAYER scope requires a player id: " + spec.id());
        }
        StateSlotKey slot = StateSlotKey.of(spec, playerId, worldKey);
        if (isTransient(spec)) {
            transientValues.put(slot, value == null ? NULL : value);
        } else {
            byte[] encoded = value == null ? null : encode(spec, value);
            store.write(slot, new StoredState(spec.dataVersion(), encoded));
        }
        notifySync(spec, slot, value);
    }

    @Override
    public void reset(@Nullable UUID playerId, @Nullable RoleKey role, ResetCause cause) {
        Objects.requireNonNull(cause, "cause");
        boolean playerScoped = cause == ResetCause.ROLE_LOST || cause == ResetCause.ROLE_ASSIGNED;
        if (playerScoped && playerId == null) {
            return;
        }
        for (RoleStateSpec<?> spec : specs.values()) {
            if (!spec.resetOn().contains(cause)) {
                continue;
            }
            if (role != null && !role.equals(spec.role())) {
                continue;
            }
            removeSlotsFor(spec, playerScoped ? playerId : null);
        }
    }

    /**
     * Drops ROUND-scope and ROUND/NONE-persistence slots for {@code worldKey}
     * (all worlds when {@code null}). Called at round end.
     */
    public synchronized void clearRoundState(@Nullable String worldKey) {
        for (RoleStateSpec<?> spec : specs.values()) {
            if (spec.scope() != StateScope.ROUND
                    && spec.persistence() != Persistence.ROUND
                    && spec.persistence() != Persistence.NONE) {
                continue;
            }
            removeSlotsFor(spec, worldKey);
        }
    }

    /**
     * Drops WORLD-scope and WORLD-persistence slots for {@code worldKey}
     * (all worlds when {@code null}). Called on world unload.
     */
    public synchronized void clearWorldState(@Nullable String worldKey) {
        for (RoleStateSpec<?> spec : specs.values()) {
            if (spec.scope() != StateScope.WORLD && spec.persistence() != Persistence.WORLD) {
                continue;
            }
            removeSlotsFor(spec, worldKey);
        }
    }

    /**
     * SERVER_STOPPING: clears session state (transient + round buckets) but
     * keeps WORLD/PERMANENT persistent slots, which a real world component
     * re-loads from NBT on the next start (fix-doc §20.2).
     */
    public synchronized void serverStop() {
        transientValues.clear();
        clearRoundState(null);
        migrationRequired.clear();
    }

    /** Snapshot of every persistent slot — used to simulate a restart round-trip. */
    public Map<StateSlotKey, StoredState> exportPersistent() {
        return store.exportAll();
    }

    /** Restores a persistent snapshot (restart simulation / diagnostics). */
    public void importPersistent(Map<StateSlotKey, StoredState> data) {
        store.importAll(data);
    }

    /** Slots whose stored version cannot be migrated (DATA_MIGRATION_REQUIRED). */
    public Set<StateSlotKey> migrationRequiredSlots() {
        return Set.copyOf(migrationRequired);
    }

    @Override
    public synchronized void freeze() {
        this.frozen = true;
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    /** Drops every stored value and (optionally) the schema table. Used by tests. */
    public synchronized void clear(boolean includingSpecs) {
        transientValues.clear();
        store.clearAll();
        migrationRequired.clear();
        if (includingSpecs) {
            specs.clear();
            frozen = false;
        }
    }

    /**
     * Formats registered specs (optionally filtered by {@code role}) and the
     * current value for {@code playerId}. Used by {@code /habitrain roleapi state}.
     */
    public List<String> describe(@Nullable UUID playerId, @Nullable RoleKey role) {
        List<String> lines = new ArrayList<>();
        for (RoleStateSpec<?> spec : specs.values()) {
            if (role != null && !role.equals(spec.role())) {
                continue;
            }
            StateSlotKey slot;
            try {
                slot = StateSlotKey.of(spec, playerId, null);
            } catch (IllegalArgumentException missingPlayer) {
                lines.add(spec.id() + " role=" + spec.role() + " scope=" + spec.scope()
                        + " persist=" + spec.persistence() + " (no player context)");
                continue;
            }
            Object value = readForDescribe(spec, slot);
            String shown = value == MISSING ? String.valueOf(spec.produceDefault()) + " (default)"
                    : value == NULL ? "null"
                    : value instanceof byte[] ? "<encoded " + ((byte[]) value).length + "b>"
                    : String.valueOf(value);
            lines.add(spec.id()
                    + " role=" + spec.role()
                    + " scope=" + spec.scope()
                    + " persist=" + spec.persistence()
                    + " reset=" + spec.resetOn()
                    + (migrationRequired.contains(slot) ? " [DATA_MIGRATION_REQUIRED]" : "")
                    + " = " + shown);
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static boolean isTransient(RoleStateSpec<?> spec) {
        Persistence p = spec.persistence();
        return p == Persistence.NONE || p == Persistence.ROUND;
    }

    /** Removes slots matching a spec, optionally narrowed to one world/player. */
    private void removeSlotsFor(RoleStateSpec<?> spec, @Nullable Object worldOrPlayer) {
        String filter = worldOrPlayer == null ? null : String.valueOf(worldOrPlayer);
        Predicate<StateSlotKey> match = key -> key.scope() == spec.scope()
                && key.id().equals(spec.id())
                && key.role().equals(spec.role())
                && (filter == null || filter.equals(
                        spec.scope() == StateScope.PLAYER
                                ? String.valueOf(key.playerId())
                                : key.worldKey()));
        transientValues.keySet().removeIf(match);
        store.removeWhere(match);
        migrationRequired.removeIf(match);
    }

    private <T> T tryDecode(RoleStateSpec<T> spec, StateSlotKey slot, StoredState stored) {
        try {
            T value = decode(spec, stored.encoded());
            if (stored.dataVersion() < spec.dataVersion()) {
                T migrated = spec.migrateValue(stored.dataVersion(), value);
                if (migrated == null) {
                    migrationRequired.add(slot);
                    LOGGER.warn("role state {} stored at v{} needs a migration chain beyond the registered one; "
                            + "keeping original bytes and returning default", slot, stored.dataVersion());
                    return spec.produceDefault();
                }
                migrationRequired.remove(slot);
                store.write(slot, new StoredState(spec.dataVersion(), encode(spec, migrated)));
                return migrated;
            }
            migrationRequired.remove(slot);
            return value;
        } catch (Throwable t) {
            migrationRequired.add(slot);
            LOGGER.warn("role state {} failed to decode v{} (opaque bytes preserved)", slot, stored.dataVersion(), t);
            return spec.produceDefault();
        }
    }

    private static <T> byte[] encode(RoleStateSpec<T> spec, T value) {
        com.mojang.serialization.Codec<T> codec = spec.codec();
        if (codec == null) {
            throw new IllegalStateException("no codec registered for " + spec.id());
        }
        try {
            JsonElement el = codec.encodeStart(JsonOps.INSTANCE, value)
                    .getOrThrow(err -> new IllegalArgumentException("encode failed: " + err));
            byte[] bytes = el.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > spec.maxSerializedBytes()) {
                throw new IllegalArgumentException(
                        "value for " + spec.id() + " encodes to " + bytes.length
                                + " bytes, over the spec cap of " + spec.maxSerializedBytes());
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalArgumentException("failed to encode " + spec.id() + ": " + t.getMessage(), t);
        }
    }

    private static <T> T decode(RoleStateSpec<T> spec, byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("encoded state payload is null");
        }
        if (bytes.length > spec.maxSerializedBytes()) {
            throw new IllegalArgumentException("stored payload for " + spec.id() + " is " + bytes.length
                    + " bytes, over the spec cap of " + spec.maxSerializedBytes());
        }
        String json = new String(bytes, StandardCharsets.UTF_8);
        JsonElement el = com.google.gson.JsonParser.parseString(json);
        return spec.codec().parse(JsonOps.INSTANCE, el)
                .getOrThrow(err -> new IllegalArgumentException("decode failed: " + err));
    }

    private <T> void notifySync(RoleStateSpec<T> spec, StateSlotKey slot, @Nullable T value) {
        var sync = spec.sync();
        if (sync == com.habitrain.core.api.role.v2.state.SyncPolicy.NONE
                || sync == com.habitrain.core.api.role.v2.state.SyncPolicy.SERVER_ONLY) {
            return;
        }
        byte[] encoded = value == null ? null : encode(spec, value);
        syncService.onChanged(spec, slot, encoded);
    }

    private Object readForDescribe(RoleStateSpec<?> spec, StateSlotKey slot) {
        if (isTransient(spec)) {
            Object v = transientValues.get(slot);
            return v == null ? MISSING : v;
        }
        StoredState stored = store.read(slot);
        if (stored == null) {
            return MISSING;
        }
        if (stored.isNull()) {
            return NULL;
        }
        try {
            return decode(spec, stored.encoded());
        } catch (Throwable t) {
            return stored.encoded();
        }
    }

    private static @Nullable ResourceLocation worldKeyOf(@Nullable ServerPlayer player) {
        if (player == null) {
            return null;
        }
        try {
            if (player.level() != null && player.level().dimension() != null) {
                return player.level().dimension().location();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Sentinel so a stored {@code null} is distinct from "never written". */
    private static final Object NULL = new Object();
    /** Sentinel used by {@link #describe} when the slot was never written. */
    private static final Object MISSING = new Object();

    record StorageKey(ResourceLocation id, RoleKey role) {
        static StorageKey of(RoleStateSpec<?> spec) {
            return new StorageKey(spec.id(), spec.role());
        }

        static StorageKey of(RoleStateKey<?> key) {
            return new StorageKey(key.id(), key.role());
        }

        @Override
        public String toString() {
            return id + "@" + role;
        }
    }

    /**
     * Public only so the provider transaction (in a sibling package) can hold a
     * rollback token.  It deliberately exposes declarations rather than the
     * package-private storage keys that implement the internal index.
     */
    public record RegistrationSnapshot(List<RoleStateSpec<?>> specs, boolean frozen) {}
}
