package com.habitrain.core.network;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.api.role.v2.RoleSnapshotId;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.config.RoleSnapshotService;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.extension.RoleOperation;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic {@code entries} stay live; HUD/instinct gameplay ids follow the
 * published snapshot while a round is fixed.
 */
class RoleSnapshotPayloadSplitTest {

    private static final String LIVE_ENTRY = "example$buff@sre:vigilante";
    private static final String SNAP_PROVIDER = "gate_provider";
    private static final String SNAP_ENTRY = "habitrain_core:pick";

    @BeforeEach
    @AfterEach
    void reset() throws Exception {
        RoleExtensionConfigService.INSTANCE.resetForTests();
        RoleSnapshotManager.INSTANCE.clear();
        setField("frozen", false);
        setField("compiledEntries", List.of());
    }

    @Test
    void lobbyGameplayFollowsLiveActiveDiagnostics() throws Exception {
        setCompiled(List.of(liveRow(EntryStatus.ACTIVE)));
        RoleSnapshotPayload payload = RoleSnapshotService.build();
        assertEquals(1, payload.entries().size());
        assertEquals("ACTIVE", payload.entries().getFirst().status());
        assertTrue(payload.gameplayEntryIds().contains(LIVE_ENTRY));
        assertTrue(payload.gameplayProviderIds().contains("example"));
    }

    @Test
    void lobbyUnionsSnapshotBehaviorsWithLiveActive() throws Exception {
        RoleSnapshot snap = new RoleSnapshot(
                new RoleSnapshotId(1),
                Map.of(), Map.of(), Set.of(),
                Set.of(new RoleSnapshot.BehaviorEntry(SNAP_PROVIDER, SNAP_ENTRY)),
                true);
        RoleSnapshotManager.INSTANCE.setLobby(snap);
        setCompiled(List.of(liveRow(EntryStatus.ACTIVE)));
        RoleSnapshotPayload payload = RoleSnapshotService.build();
        assertNull(payload.roundSnapshotId());
        assertTrue(payload.gameplayEntryIds().contains(LIVE_ENTRY));
        assertTrue(payload.gameplayEntryIds().contains(SNAP_ENTRY));
        assertTrue(payload.gameplayProviderIds().contains("example"));
        assertTrue(payload.gameplayProviderIds().contains(SNAP_PROVIDER));
    }

    @Test
    void roundGameplayStaysOnSnapshotWhenLiveDiagnosticsDisable() throws Exception {
        RoleSnapshot snap = new RoleSnapshot(
                new RoleSnapshotId(1),
                Map.of(), Map.of(), Set.of(),
                Set.of(new RoleSnapshot.BehaviorEntry(SNAP_PROVIDER, SNAP_ENTRY)),
                true);
        RoleSnapshotManager.INSTANCE.setLobby(snap);
        RoleSnapshotManager.INSTANCE.beginRound();
        setCompiled(List.of(liveRow(EntryStatus.DISABLED)));

        RoleSnapshotPayload payload = RoleSnapshotService.build();
        assertEquals("DISABLED", payload.entries().getFirst().status(),
                "Mod Menu diagnostics must track the live compiled row");
        assertFalse(payload.gameplayEntryIds().contains(LIVE_ENTRY),
                "HUD gameplay must not follow a mid-round live disable");
        assertTrue(payload.gameplayEntryIds().contains(SNAP_ENTRY));
        assertTrue(payload.gameplayEntryIds().contains("gate_provider$habitrain_core:pick"));
        assertTrue(payload.gameplayEntryIds().contains("gate_provider$habitrain_core:pick@"));
        assertEquals(List.of(SNAP_PROVIDER), payload.gameplayProviderIds());
        assertEquals("role-snapshot-v1", payload.roundSnapshotId());
    }

    @Test
    void settlementKeepsSnapshotGameplayWhenPendingExists() throws Exception {
        RoleSnapshot snap = new RoleSnapshot(
                new RoleSnapshotId(1),
                Map.of(), Map.of(), Set.of(),
                Set.of(new RoleSnapshot.BehaviorEntry(SNAP_PROVIDER, SNAP_ENTRY)),
                true);
        RoleSnapshot pending = new RoleSnapshot(
                new RoleSnapshotId(2), Map.of(), Map.of(), Set.of());
        RoleSnapshotManager.INSTANCE.setLobby(snap);
        RoleSnapshotManager.INSTANCE.beginRound();
        RoleSnapshotManager.INSTANCE.queuePending(pending);
        RoleSnapshotManager.INSTANCE.endRound();
        setCompiled(List.of(liveRow(EntryStatus.ACTIVE)));

        RoleSnapshotPayload payload = RoleSnapshotService.build();
        assertNull(payload.roundSnapshotId());
        assertEquals("role-snapshot-v2", payload.pendingSnapshotId());
        assertFalse(payload.gameplayEntryIds().contains(LIVE_ENTRY),
                "settlement must not pick up live ACTIVE rows queued for NEXT_ROUND");
        assertTrue(payload.gameplayEntryIds().contains(SNAP_ENTRY));
    }

    @Test
    void compatibilityConstructorLeavesGameplayUnset() {
        RoleSnapshotPayload payload = new RoleSnapshotPayload(
                List.of(new RoleSnapshotPayload.EntryRow(
                        LIVE_ENTRY, "example", "MODIFY", "sre:vigilante",
                        "ACTIVE", null, "active", null, null)),
                "role-snapshot-v1", null, null, "hash", "{}");
        assertNull(payload.gameplayEntryIds());
        assertNull(payload.gameplayProviderIds());
    }

    @Test
    void codecRoundTripsGameplayIdsAfterConfigJson() {
        RoleSnapshotPayload original = new RoleSnapshotPayload(
                List.of(new RoleSnapshotPayload.EntryRow(
                        LIVE_ENTRY, "example", "MODIFY", "sre:vigilante",
                        "DISABLED", "off", "entry", null, null)),
                "role-snapshot-v1",
                "role-snapshot-v1",
                null,
                "hash",
                "{}",
                List.of("gate_provider$habitrain_core:pick", "gate_provider$habitrain_core:pick@"),
                List.of(SNAP_PROVIDER));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RoleSnapshotPayload.CODEC.encode(buf, original);
        RoleSnapshotPayload decoded = RoleSnapshotPayload.CODEC.decode(buf);
        assertEquals("DISABLED", decoded.entries().getFirst().status());
        assertEquals(original.gameplayEntryIds(), decoded.gameplayEntryIds());
        assertEquals(original.gameplayProviderIds(), decoded.gameplayProviderIds());
        assertEquals("role-snapshot-v1", decoded.roundSnapshotId());
    }

    private static ManagedRoleEntry<?> liveRow(EntryStatus status) {
        return new ManagedRoleEntry<>(LIVE_ENTRY, "example", "buff",
                RoleOperation.MODIFY, RoleKey.of("sre", "vigilante"), PatchPriority.NORMAL,
                RolePatch.builder(RoleKey.of("sre", "vigilante")).defaultMax(RolePatch.IntPatch.set(2)).build(),
                status, "test", false);
    }

    private static void setCompiled(List<ManagedRoleEntry<?>> entries) throws Exception {
        setField("frozen", true);
        setField("compiledEntries", entries);
    }

    private static void setField(String name, Object value) throws Exception {
        Field field = RoleExtensionRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(RoleExtensionRegistry.INSTANCE, value);
    }
}
