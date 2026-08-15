package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.state.StateScope;
import com.habitrain.core.network.RoleStateSyncPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit P0-1 / P1-5 client-mirror behaviour: full slot identity (worldKey +
 * owner), deterministic "latest" via the monotonic revision, and removal
 * payloads deleting the mirror instead of overwriting it with a stale value.
 * Review 2026-08-14 P2: a snapshot batch drops stale mirrors on its first
 * payload, so re-tracking / re-entering a world no longer leaves ghosts.
 */
class RoleStateClientCacheTest {

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final ResourceLocation WORLD_A = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation WORLD_B = ResourceLocation.parse("minecraft:the_nether");

    @BeforeEach
    void setUp() {
        RoleStateClientCache.clear();
    }

    private static RoleStateSyncPayload value(String id, String worldKey, UUID owner, long revision) {
        return RoleStateSyncPayload.value(ResourceLocation.parse("habitrain_core:" + id), ROLE,
                StateScope.WORLD, worldKey, 1, new byte[]{1}, owner, revision);
    }

    @Test
    void worldKeyIsPartOfSlotIdentity() {
        RoleStateClientCache.accept(value("pool", WORLD_A.toString(), null, 1));
        RoleStateClientCache.accept(value("pool", WORLD_B.toString(), null, 2));

        assertNotNull(RoleStateClientCache.forSlot(ResourceLocation.parse("habitrain_core:pool"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), null), "world A mirror must be present");
        assertNotNull(RoleStateClientCache.forSlot(ResourceLocation.parse("habitrain_core:pool"),
                ROLE, StateScope.WORLD, WORLD_B.toString(), null), "world B mirror must be present");
        assertEquals(1, RoleStateClientCache.forSlot(ResourceLocation.parse("habitrain_core:pool"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), null).revision());
        assertEquals(2, RoleStateClientCache.forSlot(ResourceLocation.parse("habitrain_core:pool"),
                ROLE, StateScope.WORLD, WORLD_B.toString(), null).revision());
    }

    @Test
    void latestUsesRevisionNotDataVersion() {
        // Same dataVersion (1) but strictly increasing server revisions.
        RoleStateClientCache.accept(value("flag", WORLD_A.toString(), null, 10));
        RoleStateClientCache.accept(value("flag", WORLD_A.toString(), null, 20));

        RoleStateSyncPayload latest = RoleStateClientCache.latest(
                ResourceLocation.parse("habitrain_core:flag"), ROLE, StateScope.WORLD);
        assertNotNull(latest);
        assertEquals(20, latest.revision(), "latest must be the highest revision, not a tie");
    }

    @Test
    void removalDeletesExactSlotOnly() {
        RoleStateClientCache.accept(value("owner", WORLD_A.toString(), PLAYER_A, 1));
        RoleStateClientCache.accept(value("owner", WORLD_A.toString(), PLAYER_B, 2));
        RoleStateClientCache.accept(value("owner", WORLD_B.toString(), PLAYER_A, 3));

        RoleStateSyncPayload removal = RoleStateSyncPayload.removed(
                ResourceLocation.parse("habitrain_core:owner"), ROLE, StateScope.WORLD,
                WORLD_A.toString(), 1, PLAYER_A, 4);
        RoleStateClientCache.accept(removal);

        assertFalse(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:owner"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), PLAYER_A), "removed slot must be gone");
        assertTrue(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:owner"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), PLAYER_B), "same world other owner must survive");
        assertTrue(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:owner"),
                ROLE, StateScope.WORLD, WORLD_B.toString(), PLAYER_A), "other world same owner must survive");
    }

    @Test
    void snapshotBatchDropsStaleMirrors() {
        // Player re-tracks a target after having stopped: the server no longer
        // holds the old slot, so a full snapshot must drop the stale mirror
        // instead of leaving it until the next change (review 2026-08-14 P2).
        RoleStateClientCache.accept(value("tracked", WORLD_A.toString(), PLAYER_A, 5));
        assertTrue(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:tracked"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), PLAYER_A), "stale mirror present before snapshot");

        // Explicit begin stages a new snapshot; committed mirrors stay visible
        // until end atomically replaces them.
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotBegin(11, 6));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotValue(
                ResourceLocation.parse("habitrain_core:alive"), ROLE, StateScope.WORLD,
                WORLD_A.toString(), 1, new byte[]{9}, PLAYER_B, 7, 11));
        assertTrue(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:tracked"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), PLAYER_A),
                "begin must not clear committed mirrors before end");
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotEnd(11, 9));
        assertFalse(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:tracked"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), PLAYER_A),
                "stale mirror dropped by the completed snapshot batch");
        assertTrue(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:alive"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), PLAYER_B), "snapshot value applied");
    }

    @Test
    void interruptedSnapshotKeepsLastCommittedState() {
        RoleStateClientCache.accept(value("a", WORLD_A.toString(), null, 1));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotBegin(16, 2));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotValue(
                ResourceLocation.parse("habitrain_core:b"), ROLE, StateScope.WORLD,
                WORLD_A.toString(), 1, new byte[]{2}, null, 3, 16));
        assertTrue(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:a"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), null),
                "interrupted snapshot must not clear committed mirrors");
        assertFalse(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:b"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), null),
                "staged values must not be visible before end");
    }

    @Test
    void emptySnapshotClearsAllMirrors() {
        RoleStateClientCache.accept(value("a", WORLD_A.toString(), null, 1));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotBegin(12, 2));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotEnd(12, 3));
        assertFalse(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:a"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), null),
                "empty snapshot must clear old mirrors");
    }

    @Test
    void staleSnapshotBatchIsIgnored() {
        RoleStateClientCache.accept(value("a", WORLD_A.toString(), null, 1));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotBegin(13, 2));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotEnd(13, 3));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotBegin(12, 4));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotValue(
                ResourceLocation.parse("habitrain_core:b"), ROLE, StateScope.WORLD,
                WORLD_A.toString(), 1, new byte[]{2}, null, 5, 12));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotEnd(12, 6));
        assertFalse(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:a"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), null),
                "completed newer empty snapshot is authoritative before older batch arrives");
        assertFalse(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:b"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), null),
                "old batch value must not be committed");
    }

    @Test
    void nonSnapshotPayloadEndsTheSnapshotBatch() {
        RoleStateClientCache.accept(value("a", WORLD_A.toString(), null, 1));
        // Full snapshot applies...
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotBegin(14, 2));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotValue(
                ResourceLocation.parse("habitrain_core:b"), ROLE, StateScope.WORLD,
                WORLD_A.toString(), 1, new byte[]{2}, null, 3, 14));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotEnd(14, 4));
        assertFalse(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:a"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), null));
        // ...then an ordinary push arrives; a later snapshot starts a NEW batch
        // and clears again instead of being folded into the old one.
        RoleStateClientCache.accept(value("a", WORLD_A.toString(), null, 5));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotBegin(15, 6));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotValue(
                ResourceLocation.parse("habitrain_core:c"), ROLE, StateScope.WORLD,
                WORLD_A.toString(), 1, new byte[]{4}, null, 7, 15));
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotEnd(15, 8));
        assertFalse(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:a"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), null),
                "new snapshot batch clears mirrors that arrived after the previous batch");
        assertTrue(RoleStateClientCache.contains(ResourceLocation.parse("habitrain_core:c"),
                ROLE, StateScope.WORLD, WORLD_A.toString(), null));
    }

    @Test
    void nullValueIsNotARemoval() {
        RoleStateClientCache.accept(RoleStateSyncPayload.value(
                ResourceLocation.parse("habitrain_core:owner"), ROLE, StateScope.WORLD,
                WORLD_A.toString(), 1, null, PLAYER_A, 1));

        RoleStateSyncPayload mirror = RoleStateClientCache.forSlot(
                ResourceLocation.parse("habitrain_core:owner"), ROLE, StateScope.WORLD,
                WORLD_A.toString(), PLAYER_A);
        assertNotNull(mirror, "an explicit null value still mirrors the slot");
        assertTrue(mirror.isNull(), "encoded == null means present-but-null");
        assertFalse(mirror.removed(), "present-but-null must not be a removal");
        assertNull(RoleStateClientCache.latest(
                ResourceLocation.parse("habitrain_core:missing"), ROLE, StateScope.WORLD));
    }
}