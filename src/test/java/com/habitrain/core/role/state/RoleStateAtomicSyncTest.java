package com.habitrain.core.role.state;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.state.*;
import com.habitrain.core.client.role.RoleStateClientCache;
import com.habitrain.core.network.RoleStateSyncPayload;
import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class RoleStateAtomicSyncTest {
    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "atomic_sync_test");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private final RoleStateServiceImpl service = new RoleStateServiceImpl();
    private final List<RoleStateSyncPayload> messages = new ArrayList<>();

    @BeforeEach void prepare() {
        RoleStateClientCache.clear();
        service.syncService().setSender((owner, payload) -> messages.add(payload));
    }

    private RoleStateSpec<Integer> spec(String name, int version, Codec<Integer> codec, int cap, Persistence persistence) {
        return RoleStateSpec.of("habitrain_core", name, Integer.class).role(ROLE).codec(codec)
                .persistence(persistence).dataVersion(version).sync(SyncPolicy.OWNER)
                .maxSerializedBytes(cap).defaultValue(() -> -1).build();
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test void rejectedTransientEncodingDoesNotChangeValueOrSendRevision() {
        Codec<Integer> codec = Codec.INT.xmap(i -> i, i -> {
            if (i == 99) throw new IllegalArgumentException("rejected");
            return i;
        });
        var key = service.register(spec("transient", 1, codec, 3, Persistence.ROUND));
        service.set(key, OWNER, 5);
        long revision = messages.getLast().revision();
        assertThrows(IllegalArgumentException.class, () -> service.set(key, OWNER, 99));
        assertThrows(IllegalArgumentException.class, () -> service.set(key, OWNER, 1234));
        assertEquals(5, service.get(key, OWNER));
        assertEquals(1, messages.size());
        service.set(key, OWNER, 6);
        assertEquals(revision + 1, messages.getLast().revision());
    }

    @Test void serverOnlyUnsyncedTemporarySlotStillNeedsNoCodec() {
        var key = service.register(RoleStateSpec.of("habitrain_core", "unencoded", Object.class)
                .role(ROLE).persistence(Persistence.NONE).sync(SyncPolicy.NONE).build());
        Object value = new Object();
        service.set(key, OWNER, value);
        assertSame(value, service.get(key, OWNER));
        assertTrue(messages.isEmpty());
    }

    @Test void joinSnapshotMigratesBeforeAnyGetAndOmitsBadSlotsWithoutLosingHealthySlots() {
        var migrated = RoleStateSpec.of("habitrain_core", "migrated", Integer.class).role(ROLE)
                .persistence(Persistence.PERMANENT).codec(Codec.INT).sync(SyncPolicy.OWNER)
                .dataVersion(2).migrate(1, value -> value * 2).defaultValue(() -> -1).build();
        service.register(migrated);
        var bad = spec("bad", 2, Codec.INT, 64, Persistence.PERMANENT);
        service.register(bad);
        var corrupt = spec("corrupt", 1, Codec.INT, 64, Persistence.PERMANENT);
        service.register(corrupt);
        var healthy = service.register(spec("healthy", 1, Codec.INT, 64, Persistence.ROUND));
        service.set(healthy, OWNER, 7);
        service.importPersistent(Map.of(
                StateSlotKey.of(migrated, OWNER, null), StoredState.of(1, bytes("5")),
                StateSlotKey.of(bad, OWNER, null), StoredState.of(1, bytes("3")),
                StateSlotKey.of(corrupt, OWNER, null), StoredState.of(1, bytes("["))));
        messages.clear();
        service.sendCurrentStateTo(OWNER);
        assertTrue(messages.getFirst().snapshotBegin());
        assertTrue(messages.getLast().snapshotEnd());
        var sent = messages.stream().filter(p -> migrated.id().equals(p.id())).findFirst().orElseThrow();
        assertEquals(2, sent.dataVersion());
        assertEquals("10", new String(sent.encoded(), StandardCharsets.UTF_8));
        assertEquals(2, messages.stream().filter(RoleStateSyncPayload::snapshot).count());
        assertEquals(2, service.migrationRequiredSlots().size());
        assertEquals("3", new String(service.exportPersistent().get(StateSlotKey.of(bad, OWNER, null)).encoded(), StandardCharsets.UTF_8));
    }

    @Test void getMigrationPushesMatchingCurrentVersionToExistingOwnerMirror() {
        var spec = RoleStateSpec.of("habitrain_core", "get_migration", Integer.class).role(ROLE)
                .persistence(Persistence.PERMANENT).codec(Codec.INT).sync(SyncPolicy.OWNER)
                .dataVersion(2).migrate(1, value -> value * 2).build();
        var key = service.register(spec);
        service.importPersistent(Map.of(StateSlotKey.of(spec, OWNER, null), StoredState.of(1, bytes("5"))));
        assertEquals(10, service.get(key, OWNER));
        assertEquals(1, messages.size());
        assertEquals(2, messages.getFirst().dataVersion());
        assertEquals("10", new String(messages.getFirst().encoded(), StandardCharsets.UTF_8));
    }

    @Test void migrationWriteFailurePreservesOriginalBytesAndDoesNotSyncDefaultAsData() {
        var spec = RoleStateSpec.of("habitrain_core", "failed_write", Integer.class).role(ROLE)
                .persistence(Persistence.PERMANENT).codec(Codec.INT).sync(SyncPolicy.OWNER)
                .dataVersion(2).migrate(1, value -> value * 2).defaultValue(() -> -1).build();
        var key = service.register(spec);
        StateSlotKey slot = StateSlotKey.of(spec, OWNER, null);
        MemoryRoleStateStore underlying = new MemoryRoleStateStore();
        underlying.write(slot, StoredState.of(1, bytes("5")));
        service.setStore(new RoleStateStore() {
            public StoredState read(StateSlotKey key) { return underlying.read(key); }
            public boolean write(StateSlotKey key, StoredState state) { return false; }
            public void remove(StateSlotKey key) { underlying.remove(key); }
            public void removeWhere(Predicate<StateSlotKey> filter) { underlying.removeWhere(filter); }
            public void clearAll() { underlying.clearAll(); }
            public Collection<StateSlotKey> keys() { return underlying.keys(); }
            public Map<StateSlotKey, StoredState> exportAll() { return underlying.exportAll(); }
            public void importAll(Map<StateSlotKey, StoredState> data) { underlying.importAll(data); }
        });
        assertEquals(-1, service.get(key, OWNER));
        assertTrue(messages.isEmpty());
        service.sendCurrentStateTo(OWNER);
        assertEquals(0, messages.stream().filter(RoleStateSyncPayload::snapshot).count());
        assertEquals(1, underlying.read(slot).dataVersion());
        assertArrayEquals(bytes("5"), underlying.read(slot).encoded());
    }

    @Test void failedBatchSendCancelsStagingAndKeepsPreviouslyCommittedMirror() {
        var spec = spec("batch", 1, Codec.INT, 64, Persistence.ROUND);
        var key = service.register(spec);
        service.set(key, OWNER, 8);
        RoleStateClientCache.accept(messages.getFirst());
        service.syncService().setSender((owner, payload) -> {
            if (payload.snapshot()) throw new IllegalStateException("injected send failure");
            RoleStateClientCache.accept(payload);
            messages.add(payload);
        });
        service.sendCurrentStateTo(OWNER);
        var mirror = RoleStateClientCache.forSlot(spec.id(), ROLE, StateScope.PLAYER, null, OWNER);
        assertNotNull(mirror);
        assertEquals("8", new String(mirror.encoded(), StandardCharsets.UTF_8));
        assertTrue(messages.getLast().snapshotBegin() && messages.getLast().snapshotEnd());
    }
}
