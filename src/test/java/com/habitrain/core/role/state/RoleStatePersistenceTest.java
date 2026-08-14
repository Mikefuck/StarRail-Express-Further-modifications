package com.habitrain.core.role.state;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.state.Persistence;
import com.habitrain.core.api.role.v2.state.ResetCause;
import com.habitrain.core.api.role.v2.state.RoleStateKey;
import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import com.habitrain.core.api.role.v2.state.StateScope;
import com.habitrain.core.api.role.v2.state.SyncPolicy;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E state tests (fix-doc §18.5 / §10): world isolation, codec persistence,
 * migration chains, DATA_MIGRATION_REQUIRED preservation, max-bytes enforcement,
 * opaque unknown-slot preservation, server-stop semantics and per-policy sync
 * recipient computation.
 */
class RoleStatePersistenceTest {

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final ResourceLocation WORLD_A = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation WORLD_B = ResourceLocation.parse("minecraft:the_nether");

    private RoleStateServiceImpl store;
    private MemoryRoleStateStore memory;

    @BeforeEach
    void setUp() {
        store = new RoleStateServiceImpl();
        memory = new MemoryRoleStateStore();
        store.setStore(memory);
    }

    private static byte[] encoded(Codec<Integer> codec, int value) {
        JsonElement el = codec.encodeStart(JsonOps.INSTANCE, value).result().orElseThrow();
        return el.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // World isolation (§10.2)
    // ------------------------------------------------------------------

    @Test
    void worldScopeIsIsolatedPerWorldKey() {
        RoleStateKey<Integer> pool = store.register(RoleStateSpec.of("habitrain_core", "pool", Integer.class)
                .role(ROLE).scope(StateScope.WORLD).persistence(Persistence.WORLD)
                .codec(Codec.INT).defaultValue(() -> 0).build());
        store.set(pool, (UUID) null, WORLD_A, 7);
        assertEquals(7, store.get(pool, (UUID) null, WORLD_A));
        assertEquals(0, store.get(pool, (UUID) null, WORLD_B), "other world must not share the slot");
    }

    @Test
    void playerScopeFollowsAcrossWorlds() {
        RoleStateKey<Integer> souls = store.register(RoleStateSpec.of("habitrain_core", "souls", Integer.class)
                .role(ROLE).defaultValue(() -> 0).build());
        store.set(souls, PLAYER, WORLD_A, 5);
        assertEquals(5, store.get(souls, PLAYER, WORLD_B),
                "PLAYER scope follows the player; the world key must be ignored");
    }

    // ------------------------------------------------------------------
    // Codec persistence + migration (§10.3/§10.5)
    // ------------------------------------------------------------------

    @Test
    void persistentSlotSurvivesServerStop() {
        RoleStateKey<Integer> perm = store.register(RoleStateSpec.of("habitrain_core", "perm", Integer.class)
                .role(ROLE).scope(StateScope.WORLD).persistence(Persistence.PERMANENT)
                .codec(Codec.INT).defaultValue(() -> 0).build());
        store.set(perm, (UUID) null, WORLD_A, 11);
        store.serverStop();
        assertEquals(11, store.get(perm, (UUID) null, WORLD_A), "PERMANENT must survive a server stop");
    }

    @Test
    void transientRoundStateClearedOnServerStop() {
        RoleStateKey<Integer> flag = store.register(RoleStateSpec.of("habitrain_core", "flag", Integer.class)
                .role(ROLE).scope(StateScope.ROUND).defaultValue(() -> -1).build());
        store.set(flag, (UUID) null, WORLD_A, 3);
        store.serverStop();
        assertEquals(-1, store.get(flag, (UUID) null, WORLD_A), "ROUND transient state must clear on server stop");
    }

    @Test
    void migrationChainMovesStoredVersionToCurrent() {
        RoleStateSpec<Integer> spec = RoleStateSpec.of("habitrain_core", "mig", Integer.class)
                .role(ROLE).scope(StateScope.WORLD).persistence(Persistence.WORLD)
                .dataVersion(2).codec(Codec.INT).migrate(1, v -> v * 2).defaultValue(() -> -1).build();
        RoleStateKey<Integer> key = store.register(spec);
        StateSlotKey slot = StateSlotKey.of(spec, null, WORLD_A);
        memory.write(slot, StoredState.of(1, encoded(Codec.INT, 5)));

        assertEquals(10, store.get(key, (UUID) null, WORLD_A), "v1 -> v2 migration must double the value");
        assertTrue(store.migrationRequiredSlots().isEmpty());
    }

    @Test
    void missingMigrationChainFlagsSlotAndKeepsOpaqueBytes() {
        RoleStateSpec<Integer> spec = RoleStateSpec.of("habitrain_core", "nomig", Integer.class)
                .role(ROLE).scope(StateScope.WORLD).persistence(Persistence.WORLD)
                .dataVersion(3).codec(Codec.INT).defaultValue(() -> -1).build();
        RoleStateKey<Integer> key = store.register(spec);
        StateSlotKey slot = StateSlotKey.of(spec, null, WORLD_A);
        byte[] opaque = encoded(Codec.INT, 5);
        memory.write(slot, StoredState.of(1, opaque));

        assertEquals(-1, store.get(key, (UUID) null, WORLD_A),
                "unmigratable slot returns the default and does not crash");
        assertTrue(store.migrationRequiredSlots().contains(slot), "slot must be flagged DATA_MIGRATION_REQUIRED");
        StoredState kept = memory.read(slot);
        assertEquals(1, kept.dataVersion(), "original version must be preserved for recovery");
        assertEquals(5, Integer.parseInt(new String(kept.encoded(), StandardCharsets.UTF_8)),
                "original bytes must stay untouched");
    }

    @Test
    void maxSerializedBytesEnforcedOnPersistentWrite() {
        RoleStateKey<Integer> tiny = store.register(RoleStateSpec.of("habitrain_core", "tiny", Integer.class)
                .role(ROLE).scope(StateScope.WORLD).persistence(Persistence.PERMANENT).codec(Codec.INT)
                .maxSerializedBytes(4).defaultValue(() -> 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> store.set(tiny, (UUID) null, WORLD_A, 123_456_789));
        store.set(tiny, (UUID) null, WORLD_A, 5);
        assertEquals(5, store.get(tiny, (UUID) null, WORLD_A));
    }

    @Test
    void opaqueUnknownSlotSurvivesWrites() {
        StateSlotKey ghost = new StateSlotKey(WORLD_A.toString(), null, StateScope.WORLD,
                ResourceLocation.parse("ghostmod:ghost_state"), ROLE);
        memory.write(ghost, StoredState.of(2, new byte[]{1, 2, 3}));

        RoleStateKey<Integer> pool = store.register(RoleStateSpec.of("habitrain_core", "pool", Integer.class)
                .role(ROLE).scope(StateScope.WORLD).persistence(Persistence.WORLD)
                .codec(Codec.INT).defaultValue(() -> 0).build());
        store.set(pool, (UUID) null, WORLD_A, 7);
        store.reset((UUID) null, null, ResetCause.ROUND_END);

        StoredState kept = memory.read(ghost);
        assertTrue(kept != null && kept.dataVersion() == 2,
                "an unknown provider slot must never be touched by platform sweeps");
    }

    // ------------------------------------------------------------------
    // Sync recipient computation (§10.4)
    // ------------------------------------------------------------------

    @Test
    void ownerPolicyReachesOnlyTheOwner() {
        List<UUID> delivered = new ArrayList<>();
        store.syncService().setRecipients(new RoleStateSyncService.RecipientProvider() {
            @Override
            public java.util.Collection<UUID> allOnline() {
                return List.of(PLAYER, PLAYER_B);
            }

            @Override
            public java.util.Collection<UUID> inWorld(String worldKey) {
                return List.of(PLAYER_B);
            }
        });
        store.syncService().setSender((uuid, payload) -> delivered.add(uuid));

        RoleStateKey<Integer> owner = store.register(RoleStateSpec.of("habitrain_core", "owner", Integer.class)
                .role(ROLE).sync(SyncPolicy.OWNER).codec(Codec.INT).defaultValue(() -> 0).build());
        store.set(owner, PLAYER, WORLD_A, 1);
        assertEquals(List.of(PLAYER), delivered, "OWNER must reach exactly the owning player");
    }

    @Test
    void ownerAndTrackingReachesOwnerPlusTrackersForPlayerScope() {
        List<UUID> delivered = new ArrayList<>();
        store.syncService().setRecipients(new RoleStateSyncService.RecipientProvider() {
            @Override
            public java.util.Collection<UUID> allOnline() {
                return List.of(PLAYER, PLAYER_B);
            }

            @Override
            public java.util.Collection<UUID> inWorld(String worldKey) {
                // Deliberately empty: PLAYER scope must resolve trackers, not world membership.
                return List.of();
            }

            @Override
            public java.util.Collection<UUID> trackersOf(java.util.UUID playerId) {
                return PLAYER.equals(playerId) ? List.of(PLAYER_B) : List.of();
            }
        });
        store.syncService().setSender((uuid, payload) -> delivered.add(uuid));

        RoleStateKey<Integer> tracked = store.register(RoleStateSpec.of("habitrain_core", "tracked", Integer.class)
                .role(ROLE).sync(SyncPolicy.OWNER_AND_TRACKING).codec(Codec.INT).defaultValue(() -> 0).build());
        store.set(tracked, PLAYER, WORLD_A, 1);
        assertEquals(List.of(PLAYER, PLAYER_B), delivered,
                "PLAYER-scope OWNER_AND_TRACKING must reach the owner plus their trackers");
    }

    @Test
    void allPolicyReachesEveryOnlinePlayer() {
        List<UUID> delivered = new ArrayList<>();
        store.syncService().setRecipients(new RoleStateSyncService.RecipientProvider() {
            @Override
            public java.util.Collection<UUID> allOnline() {
                return List.of(PLAYER, PLAYER_B);
            }

            @Override
            public java.util.Collection<UUID> inWorld(String worldKey) {
                return List.of();
            }
        });
        store.syncService().setSender((uuid, payload) -> delivered.add(uuid));

        RoleStateKey<Integer> all = store.register(RoleStateSpec.of("habitrain_core", "all", Integer.class)
                .role(ROLE).sync(SyncPolicy.ALL).codec(Codec.INT).defaultValue(() -> 0).build());
        store.set(all, PLAYER, WORLD_A, 1);
        assertEquals(2, delivered.size());
        assertTrue(delivered.contains(PLAYER));
        assertTrue(delivered.contains(PLAYER_B));
    }

    @Test
    void noneAndServerOnlyNeverSend() {
        List<UUID> delivered = new ArrayList<>();
        store.syncService().setRecipients(new RoleStateSyncService.RecipientProvider() {
            @Override
            public java.util.Collection<UUID> allOnline() {
                return List.of(PLAYER);
            }

            @Override
            public java.util.Collection<UUID> inWorld(String worldKey) {
                return List.of(PLAYER);
            }
        });
        store.syncService().setSender((uuid, payload) -> delivered.add(uuid));

        RoleStateKey<Integer> none = store.register(RoleStateSpec.of("habitrain_core", "none", Integer.class)
                .role(ROLE).sync(SyncPolicy.NONE).defaultValue(() -> 0).build());
        RoleStateKey<Integer> serverOnly = store.register(RoleStateSpec.of("habitrain_core", "srv", Integer.class)
                .role(ROLE).sync(SyncPolicy.SERVER_ONLY).codec(Codec.INT).defaultValue(() -> 0).build());
        store.set(none, PLAYER, WORLD_A, 1);
        store.set(serverOnly, PLAYER, WORLD_A, 2);
        assertTrue(delivered.isEmpty(), "NONE and SERVER_ONLY must never hit the wire");
    }

    // ------------------------------------------------------------------
    // Registration contract (§10.6)
    // ------------------------------------------------------------------

    @Test
    void persistentWithoutCodecIsRejected() {
        assertThrows(IllegalStateException.class, () ->
                RoleStateSpec.of("habitrain_core", "bad", Integer.class)
                        .role(ROLE).persistence(Persistence.WORLD).defaultValue(() -> 0).build());
    }

    @Test
    void syncWithoutCodecIsRejected() {
        assertThrows(IllegalStateException.class, () ->
                RoleStateSpec.of("habitrain_core", "bad", Integer.class)
                        .role(ROLE).sync(SyncPolicy.ALL).defaultValue(() -> 0).build());
    }

    @Test
    void brokenMigrationChainIsRejected() {
        // Non-continuous chain (must start at v1) is a caller error.
        assertThrows(IllegalArgumentException.class, () ->
                RoleStateSpec.of("habitrain_core", "badmig", Integer.class)
                        .role(ROLE).dataVersion(3).codec(Codec.INT)
                        .migrate(2, v -> v + 1).build());
        // Chain that does not reach the declared dataVersion is an invalid spec.
        assertThrows(IllegalStateException.class, () ->
                RoleStateSpec.of("habitrain_core", "badmig2", Integer.class)
                        .role(ROLE).dataVersion(3).codec(Codec.INT)
                        .migrate(1, v -> v + 1).build());
    }

    @Test
    void roundEndClearsRoundPersistenceButNotPermanent() {
        RoleStateKey<Integer> round = store.register(RoleStateSpec.of("habitrain_core", "round", Integer.class)
                .role(ROLE).scope(StateScope.WORLD).persistence(Persistence.ROUND)
                .codec(Codec.INT).defaultValue(() -> -1).build());
        RoleStateKey<Integer> perm = store.register(RoleStateSpec.of("habitrain_core", "perm", Integer.class)
                .role(ROLE).scope(StateScope.WORLD).persistence(Persistence.PERMANENT).codec(Codec.INT)
                .resetOn(ResetCause.MANUAL).defaultValue(() -> 0).build());
        store.set(round, (UUID) null, WORLD_A, 3);
        store.set(perm, (UUID) null, WORLD_A, 9);

        store.reset((UUID) null, null, ResetCause.ROUND_END);

        assertEquals(-1, store.get(round, (UUID) null, WORLD_A));
        assertEquals(9, store.get(perm, (UUID) null, WORLD_A), "MANUAL-only PERMANENT survives ROUND_END");
    }

    @Test
    void worldUnloadClearsWorldScope() {
        RoleStateKey<Integer> pool = store.register(RoleStateSpec.of("habitrain_core", "pool", Integer.class)
                .role(ROLE).scope(StateScope.WORLD).persistence(Persistence.WORLD)
                .codec(Codec.INT).resetOn(ResetCause.MANUAL).defaultValue(() -> 0).build());
        store.set(pool, (UUID) null, WORLD_A, 4);
        store.clearWorldState(WORLD_A.toString());
        assertEquals(0, store.get(pool, (UUID) null, WORLD_A));
    }
}
