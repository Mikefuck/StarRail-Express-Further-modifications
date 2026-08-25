package com.habitrain.core.api.role.v2;

import com.habitrain.core.role.catalog.RoleCatalogImpl;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
import com.habitrain.core.role.override.EffectiveSnapshot;
import com.habitrain.core.role.override.RoleOverrideEngine;
import com.habitrain.core.role.snapshot.RoleSnapshotArchive;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import com.habitrain.core.role.snapshot.RoleSnapshotVersions;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G10 catalog/snapshot contract: settlement slots after {@code endRound}, unique
 * published generations, restore-miss-is-empty, and lazy-compile temp ids.
 */
class G10SnapshotContractTest {

    private static final ResourceLocation ROLE_ID = ResourceLocation.parse("sre:civilian");
    private static final int COLOR = 0xFF36E51B;

    @BeforeEach
    void reset() throws Exception {
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "managedRoles", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "compiledReplacements", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "patches", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "replacements", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "aliases", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "replacementByTarget", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "registeredEntryIds", new LinkedHashSet<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "frozen", false);
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "tmmAccessible", false);
        RoleSnapshotManager.INSTANCE.clear();
        RoleSnapshotArchive.INSTANCE.clear();
        RoleSnapshotVersions.resetForTests();
        RoleRuntimeOverlayApplier.clear();
        setSnapshot(new EffectiveSnapshot(Map.of(), Map.of(), List.of()));
    }

    @Test
    void settlementSlotsSurviveEndRoundUntilPendingActivates() {
        RoleSnapshot lobby = emptySnapshot(new RoleSnapshotId(1));
        RoleSnapshot pending = emptySnapshot(new RoleSnapshotId(2));
        RoleCatalogImpl api = new RoleCatalogImpl(Map.of());

        RoleSnapshotManager.INSTANCE.setLobby(lobby);
        RoleSnapshotManager.INSTANCE.beginRound();
        RoleSnapshotManager.INSTANCE.queuePending(pending);
        RoleSnapshotManager.INSTANCE.endRound();

        assertEquals(lobby, RoleSnapshotManager.INSTANCE.current(),
                "current() stays on the ended round until pending activates");
        assertEquals(lobby, api.lastEndedSnapshot().orElseThrow(),
                "lastEndedSnapshot() is the just-ended catalog");
        assertEquals(lobby, api.currentSnapshot().orElseThrow());
        assertTrue(api.roundSnapshot().isEmpty(),
                "roundSnapshot() is empty after endRound cleared the round slot");
        assertNull(RoleSnapshotManager.INSTANCE.round());
        assertEquals(lobby, RoleSnapshotManager.INSTANCE.lastEnded());

        RoleSnapshotManager.INSTANCE.activatePending();
        assertTrue(api.lastEndedSnapshot().isEmpty(),
                "activatePending() drops the settlement slot");
        assertEquals(pending, RoleSnapshotManager.INSTANCE.current(),
                "current() becomes pending-as-lobby");
        assertEquals(pending, api.currentSnapshot().orElseThrow());
        assertTrue(api.roundSnapshot().isEmpty());
    }

    @Test
    void successiveMidRoundGenerationsStayRestorableAndUnknownIdIsEmpty() {
        SRERole live = role(ROLE_ID);
        RoleKey key = RoleKey.of(ROLE_ID);
        RoleCatalogImpl api = new RoleCatalogImpl(Map.of(ROLE_ID, live));

        RoleSnapshotId firstId = RoleSnapshotVersions.nextPublished();
        RoleSnapshot first = snapshotWith(firstId, live);
        RoleSnapshotManager.INSTANCE.setLobby(first);
        RoleSnapshotManager.INSTANCE.beginRound();

        RoleSnapshotId secondId = RoleSnapshotVersions.nextPublished();
        RoleSnapshot second = snapshotWith(secondId, live);
        RoleSnapshotManager.INSTANCE.queuePending(second);

        RoleSnapshotId thirdId = RoleSnapshotVersions.nextPublished();
        RoleSnapshot third = snapshotWith(thirdId, live);
        RoleSnapshotManager.INSTANCE.queuePending(third);

        assertNotEquals(firstId, secondId);
        assertNotEquals(secondId, thirdId);
        RoleSnapshot archivedFirst = RoleSnapshotArchive.INSTANCE.get(firstId);
        RoleSnapshot archivedSecond = RoleSnapshotArchive.INSTANCE.get(secondId);
        RoleSnapshot archivedThird = RoleSnapshotArchive.INSTANCE.get(thirdId);
        assertNotNull(archivedFirst, "archive keeps the first mid-round generation");
        assertNotNull(archivedSecond, "archive keeps the overwritten pending generation");
        assertNotNull(archivedThird);
        assertEquals(first.id(), archivedFirst.id());
        assertEquals(second.id(), archivedSecond.id());
        assertEquals(third.id(), archivedThird.id());

        assertTrue(api.restore(firstId, key).isPresent(),
                "restore(firstId) still finds the first generation");
        assertTrue(api.restore(secondId, key).isPresent());
        assertTrue(api.restore(thirdId, key).isPresent());

        RoleSnapshotId unknown = new RoleSnapshotId(404);
        assertTrue(api.currentSnapshot().isPresent(), "live current is the round snapshot");
        assertTrue(api.find(key).isPresent(), "current catalog still has the role");
        assertTrue(api.restore(unknown, key).isEmpty(),
                "restore of an unknown id is empty, not current()");
    }

    @Test
    void publishedGenerationsDoNotBindToV1EngineVersion() {
        long v1 = RoleOverrideEngine.getInstance().getSnapshotVersion();
        RoleSnapshotId a = RoleSnapshotVersions.nextPublished();
        RoleSnapshotId b = RoleSnapshotVersions.nextPublished();
        assertNotEquals(a, b);
        assertNotEquals(RoleSnapshotVersions.TEMP, a);
        assertEquals(v1, RoleOverrideEngine.getInstance().getSnapshotVersion(),
                "catalog generations must not increment v1 snapshotVersion");
    }

    @Test
    void lazyCompileUsesNonArchivedTempId() {
        SRERole live = role(ROLE_ID);
        RoleCatalogImpl api = new RoleCatalogImpl(Map.of(ROLE_ID, live));

        assertFalse(api.currentSnapshot().isPresent());
        assertEquals(RoleSnapshotVersions.TEMP, api.snapshot());
        assertTrue(api.find(RoleKey.of(ROLE_ID)).isPresent(),
                "lazy compile still answers from the injected raw map");
        assertNull(RoleSnapshotArchive.INSTANCE.get(RoleSnapshotVersions.TEMP),
                "temp lazy compiles must not enter the archive");
        assertTrue(api.restore(RoleSnapshotVersions.TEMP, RoleKey.of(ROLE_ID)).isEmpty(),
                "restore of the temp id is a miss");
        assertEquals(RoleSnapshotVersions.TEMP, api.snapshot(),
                "lazy compile must not consume a published generation");
    }

    private static RoleSnapshot emptySnapshot(RoleSnapshotId id) {
        return new RoleSnapshot(id, Map.of(), Map.of(), Set.of());
    }

    private static RoleSnapshot snapshotWith(RoleSnapshotId id, SRERole role) {
        return new RoleSnapshot(id,
                Map.of(role.identifier(), new EffectiveRole(RoleKey.of(role.identifier()), role,
                        EffectiveRole.Source.BASELINE)),
                Map.of(),
                Set.of());
    }

    private static SRERole role(ResourceLocation id) {
        return new NormalRole(id, COLOR, true, false, SRERole.MoodType.REAL, 20, false);
    }

    private static void setSnapshot(EffectiveSnapshot snap) throws Exception {
        Field field = RoleOverrideEngine.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        field.set(RoleOverrideEngine.getInstance(), snap);
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
