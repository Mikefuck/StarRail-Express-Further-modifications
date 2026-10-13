package com.habitrain.core.scene.server;

import com.habitrain.core.api.scene.SceneInstanceSpec;
import com.habitrain.core.api.scene.SceneInstanceView;
import com.habitrain.core.api.scene.SceneListener;
import com.habitrain.core.api.scene.model.SceneInstance;
import com.habitrain.core.scene.network.SceneInstancesS2C;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SceneInstanceServiceTest {
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final String SOURCE = "minecraft:overworld";
    private static final String TARGET = "minecraft:the_nether";

    @Test
    void changingAudienceRevokesEachPreviousMirrorAndDoesNotRestoreExcludedPlayers() {
        FakeRuntime runtime = new FakeRuntime();
        SceneInstanceService service = new SceneInstanceService(runtime);
        assertTrue(service.spawn(spec().build()).isSuccess());
        runtime.messages.clear();

        assertTrue(service.mutate("test:scene", builder -> builder.visibleTo(Set.of(A))));
        assertEquals(List.of("upsert"), runtime.sequence(A));
        assertEquals(List.of("remove"), runtime.sequence(B));
        runtime.messages.clear();

        assertTrue(service.mutate("test:scene", builder -> builder.visibleTo(Set.of(B))));
        assertEquals(List.of("remove"), runtime.sequence(A));
        assertEquals(List.of("upsert"), runtime.sequence(B));
        runtime.messages.clear();
        assertTrue(service.mutate("test:scene", builder -> builder.priority(9)));
        assertEquals(List.of(), runtime.sequence(A));
        assertEquals(List.of("upsert"), runtime.sequence(B));
    }

    @Test
    void upsertToAnotherDimensionRemovesOldRecipientsAndUpsertsOnlyNewDimension() {
        FakeRuntime runtime = new FakeRuntime();
        SceneInstanceService service = new SceneInstanceService(runtime);
        service.spawn(spec().build());
        runtime.messages.clear();
        assertTrue(service.upsert(spec().dimension(TARGET).build()).isSuccess());
        assertEquals(List.of("remove"), runtime.sequence(A));
        assertEquals(List.of("remove"), runtime.sequence(B));
        assertEquals(List.of("upsert"), runtime.sequence(C));
        assertEquals(TARGET, service.get("test:scene").orElseThrow().spec().dimensionKey());
    }

    @Test
    void disabledMigrationRetainsInstanceAndPhaseAndIsAvailableWhenEnabledAgain() {
        FakeRuntime runtime = new FakeRuntime();
        SceneInstanceService service = new SceneInstanceService(runtime);
        List<String> events = new ArrayList<>();
        service.addListener(new SceneListener() {
            public void onInstanceRemoved(SceneInstanceView view, String reason) { events.add(reason); }
            public void onInstanceUpdated(SceneInstanceView view) { events.add("updated"); }
        });
        service.spawn(spec().startGameTime(70).durationTicks(200).build());
        SceneInstance old = service.get("test:scene").orElseThrow();
        runtime.enabled = false;
        runtime.messages.clear();
        assertTrue(service.moveToDimension("test:scene", TARGET));
        SceneInstance moved = service.get("test:scene").orElseThrow();
        assertEquals(TARGET, moved.dimensionKey());
        assertEquals(TARGET, moved.spec().dimensionKey());
        assertEquals(970, moved.startGameTime());
        assertEquals(1200, moved.expireAtGameTime());
        assertEquals(old.revision() + 1, moved.revision());
        assertEquals(old.createdAtMillis(), moved.createdAtMillis());
        assertEquals(List.of("dimension_changed", "updated"), events);
        assertEquals(List.of("remove"), runtime.sequence(A));
        assertEquals(List.of(), runtime.sequence(C));
        runtime.messages.clear();
        runtime.enabled = true;
        assertTrue(service.resync("test:scene"));
        assertEquals(List.of("upsert"), runtime.sequence(C));
    }

    @Test
    void unavailableTargetAndConstructionFailureLeaveOldInstanceAndNotificationsUntouched() {
        FakeRuntime runtime = new FakeRuntime();
        SceneInstanceService service = new SceneInstanceService(runtime);
        service.spawn(spec().build());
        SceneInstance old = service.get("test:scene").orElseThrow();
        List<String> events = new ArrayList<>();
        service.addListener(new SceneListener() {
            public void onInstanceRemoved(SceneInstanceView view, String reason) { events.add(reason); }
            public void onInstanceUpdated(SceneInstanceView view) { events.add("updated"); }
        });
        runtime.messages.clear();
        assertFalse(service.moveToDimension("test:scene", "test:missing"));
        runtime.failAsset = true;
        assertFalse(service.moveToDimension("test:scene", TARGET));
        assertSame(old, service.get("test:scene").orElseThrow());
        assertTrue(runtime.messages.isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    void pausedMigrationPreservesFrozenPhaseAndRemainingLifetime() {
        FakeRuntime runtime = new FakeRuntime();
        SceneInstanceService service = new SceneInstanceService(runtime);
        service.spawn(spec().startGameTime(70).paused(true).durationTicks(200).build());
        runtime.times.put(SOURCE, 150L);
        assertTrue(service.moveToDimension("test:scene", TARGET));
        SceneInstance moved = service.get("test:scene").orElseThrow();
        assertEquals(970, moved.startGameTime());
        assertEquals(1000, moved.pausedAtGameTime());
        assertEquals(1150, moved.expireAtGameTime());
    }

    private static SceneInstanceSpec.Builder spec() {
        return SceneInstanceSpec.builder("test:scene").assetKey("test_asset").dimension(SOURCE);
    }

    private static final class FakeRuntime implements SceneInstanceService.RuntimeAccess {
        boolean enabled = true;
        boolean failAsset;
        final Map<String, Long> times = new HashMap<>(Map.of(SOURCE, 100L, TARGET, 1000L));
        final Map<UUID, List<SceneInstancesS2C>> messages = new HashMap<>();
        public boolean globalEnabled() { return enabled; }
        public Long gameTime(String dimension) { return times.get(dimension); }
        public List<SceneInstanceService.Recipient> recipients() {
            return List.of(new SceneInstanceService.Recipient(A, SOURCE),
                    new SceneInstanceService.Recipient(B, SOURCE), new SceneInstanceService.Recipient(C, TARGET));
        }
        public String assetHash(String assetKey) {
            if (failAsset) throw new IllegalStateException("injected asset resolution failure");
            return "";
        }
        public boolean send(UUID player, SceneInstancesS2C payload) {
            messages.computeIfAbsent(player, ignored -> new ArrayList<>()).add(payload);
            return true;
        }
        public void manifest(UUID player, SceneInstance instance) {}
        List<String> sequence(UUID player) {
            return messages.getOrDefault(player, List.of()).stream()
                    .map(payload -> payload.removals().isEmpty() ? "upsert" : "remove").toList();
        }
    }
}
