package com.habitrain.core.role.extension;

import com.habitrain.core.api.*;
import com.habitrain.core.api.role.v2.*;
import com.habitrain.core.api.role.v2.action.*;
import com.habitrain.core.api.role.v2.behavior.*;
import com.habitrain.core.role.action.RoleActionServiceImpl;
import com.habitrain.core.role.behavior.RoleHookRegistry;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.snapshot.*;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.vote.OptionVoteManager;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real Minecraft objects run in an isolated bootstrapped test JVM. */
class ApiRuntimeRegressionTest {
    private static final String PROVIDER = "audit_provider";
    private static final RoleKey ROLE = RoleKey.of("starrailexpress", "civilian");
    private RoleExtensionRegistry.RegistrationSnapshot registrySnapshot;
    private RoleActionServiceImpl actions;

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @BeforeEach void prepare() {
        registrySnapshot = RoleExtensionRegistry.INSTANCE.snapshotForTransaction(false);
        actions = (RoleActionServiceImpl) RoleActionApi.instance();
        actions.clear(true);
        RoleHookRegistry.INSTANCE.clear();
        RoleSnapshotManager.INSTANCE.clear();
        RoleExtensionConfigService.INSTANCE.resetForTests();
        TaskManager.getInstance().clearAll();
        OptionVoteManager.resetAll();
    }
    @AfterEach void cleanup() {
        actions.clear(true);
        actions.setS2cSender(null);
        actions.setResultSender(null);
        actions.setCurrentRoleLookup(null);
        actions.setAliveLookup(null);
        RoleHookRegistry.INSTANCE.clear();
        RoleSnapshotManager.INSTANCE.clear();
        RoleExtensionConfigService.INSTANCE.resetForTests();
        RoleExtensionRegistry.INSTANCE.restoreTransactionSnapshot(registrySnapshot);
        TaskManager.getInstance().clearAll();
        OptionVoteManager.resetAll();
    }

    @ParameterizedTest @EnumSource(RoleActionDirection.class)
    void publicRegistrationRealCompilerAndBothNetworkDirectionsKeepRoundGates(RoleActionDirection direction) throws Exception {
        var tx = RoleExtensionRegistry.INSTANCE.begin(PROVIDER);
        RoleExtensionRegistrar registrar = new ScopedRoleExtensionRegistrar(tx);
        var id = ResourceLocation.parse(PROVIDER + ":action");
        AtomicInteger handled = new AtomicInteger();
        registrar.action(RoleActionSpec.of(id).role(ROLE).direction(direction).ratePerSecond(100)
                .handler(ctx -> { handled.incrementAndGet(); return RoleActionResult.success(); }).build());
        registrar.hooks(ROLE, RoleHooks.builder().lifecycle(new RoleLifecycleHooks() {}).build());
        tx.commit();
        ServerPlayer player = allocate(ServerPlayer.class);
        player.setUUID(UUID.randomUUID());
        actions.setCurrentRoleLookup(p -> ROLE);
        actions.setAliveLookup(p -> true);
        AtomicInteger pushes = new AtomicInteger();
        actions.setS2cSender((p, action, bytes) -> pushes.incrementAndGet());
        var snapshot = RoleSnapshotCompiler.compile(new RoleSnapshotId(1001), Map.of());
        assertTrue(snapshot.isBehaviorEntryEnabled(PROVIDER, id.toString()));
        assertTrue(snapshot.enabledBehaviorEntries().stream().anyMatch(e -> e.entryId().contains("$hooks:")));
        RoleSnapshotManager.INSTANCE.setLobby(snapshot);
        checkDirections(player, id, direction, 1, handled, pushes, 1);
        RoleSnapshotManager.INSTANCE.beginRound();
        RoleExtensionConfigService.INSTANCE.setProviderEnabled(PROVIDER, false);
        RoleSnapshotManager.INSTANCE.queuePending(RoleSnapshotCompiler.compile(new RoleSnapshotId(1002), Map.of()));
        checkDirections(player, id, direction, 2, handled, pushes, 2);
        RoleSnapshotManager.INSTANCE.endRound();
        RoleSnapshotManager.INSTANCE.activatePending();
        assertEquals(RoleActionResult.CONFIG_DISABLED, actions.receiveC2S(player, id, new byte[0], 3).reasonKey());
        actions.sendTo(player, id, new byte[0]);
        assertEquals(direction == RoleActionDirection.C2S ? 0 : 2, pushes.get());
        RoleExtensionConfigService.INSTANCE.setProviderEnabled(PROVIDER, true);
        RoleExtensionConfigService.INSTANCE.setEntryEnabled(id.toString(), false);
        assertFalse(RoleSnapshotCompiler.compile(new RoleSnapshotId(1003), Map.of()).isBehaviorEntryEnabled(PROVIDER, id.toString()));
        RoleExtensionConfigService.INSTANCE.setEntryEnabled(id.toString(), true);
        RoleSnapshotManager.INSTANCE.setLobby(RoleSnapshotCompiler.compile(new RoleSnapshotId(1004), Map.of()));
        checkDirections(player, id, direction, 4, handled, pushes, 3);
    }

    private void checkDirections(ServerPlayer player, ResourceLocation id, RoleActionDirection direction,
                                 int sequence, AtomicInteger handled, AtomicInteger pushes, int expected) {
        if (direction != RoleActionDirection.S2C) assertTrue(actions.receiveC2S(player, id, new byte[0], sequence).ok());
        actions.sendTo(player, id, new byte[0]);
        assertEquals(direction == RoleActionDirection.S2C ? 0 : expected, handled.get());
        assertEquals(direction == RoleActionDirection.C2S ? 0 : expected, pushes.get());
    }

    @Test void callbackErrorStillReclaimsAndKeepsOriginalError() throws Exception {
        Player player = player();
        var failure = new IllegalStateException("provider reclaim failed");
        var task = new TaskInstance(new TaskDefinition.Builder(PROVIDER, "gift")
                .onReclaim((p, t) -> { throw failure; }).build());
        var sword = grant(player, task);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> ItemReclaimHelper.reclaimForTask(player, task)));
        assertTrue(sword.isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "fail", "timeout", "reconnect"})
    void taskRemovalPathsReclaimAfterProviderThrows(String path) throws Exception {
        Player player = player();
        AtomicInteger callbacks = new AtomicInteger();
        var task = new TaskInstance(new TaskDefinition.Builder(PROVIDER, "gift").timeLimit(1)
                .onReclaim((p, t) -> { callbacks.incrementAndGet(); throw new IllegalStateException("provider fault"); }).build());
        var sword = grant(player, task);
        var manager = TaskManager.getInstance();
        manager.setActiveTask(player.getUUID(), task);
        if (path.equals("fail")) task.markFailed();
        if (path.equals("timeout")) {
            for (int i = 0; i < 20; i++) task.tick(player);
            assertTrue(task.isFailed());
        }
        if (path.equals("reconnect")) {
            task.setDimension(Level.OVERWORLD);
            manager.unbindOwner(player.getUUID());
            manager.clearActiveTasksForLevel(Level.OVERWORLD);
            assertFalse(sword.isEmpty());
            manager.flushPendingReclaim(player);
            manager.flushPendingReclaim(player);
        } else manager.cancelTrackedTask(player, task);
        assertTrue(sword.isEmpty());
        assertNull(manager.getActiveTask(player.getUUID()));
        assertEquals(1, callbacks.get());
    }

    @Test void invalidVoteDoesNotTouchWorldOrExistingSession() throws Exception {
        ServerLevel unavailableWorld = allocate(ServerLevel.class);
        List<VoteOption> tooMany = java.util.stream.IntStream.range(0, 65).mapToObj(i -> new VoteOption("id" + i, "Name")).toList();
        assertFalse(OptionVoteManager.start(unavailableWorld, "audit", "", "", tooMany, 10, null));
        assertFalse(OptionVoteManager.start(unavailableWorld, "audit", "x".repeat(129), "", List.of(new VoteOption("a", "A")), 10, null));
        assertFalse(OptionVoteManager.start(unavailableWorld, "audit", "", "x".repeat(257), List.of(new VoteOption("a", "A")), 10, null));
        var dimension = Level.class.getDeclaredField("dimension");
        dimension.setAccessible(true);
        dimension.set(unavailableWorld, Level.OVERWORLD);
        var players = ServerLevel.class.getDeclaredField("players");
        players.setAccessible(true);
        players.set(unavailableWorld, new ArrayList<ServerPlayer>());
        assertTrue(OptionVoteManager.start(unavailableWorld, "existing", "", "", List.of(new VoteOption("a", "A")), 10, null));
        assertFalse(OptionVoteManager.start(unavailableWorld, "replacement", "", "", tooMany, 10, null));
        assertEquals("existing", OptionVoteManager.currentVoteId(unavailableWorld));
    }

    @Test void failedOpeningBroadcastReleasesVoteSession() throws Exception {
        ServerLevel unavailableWorld = allocate(ServerLevel.class);
        var dimension = Level.class.getDeclaredField("dimension");
        dimension.setAccessible(true);
        dimension.set(unavailableWorld, Level.OVERWORLD);
        // players() on the uninitialized host fails inside the real broadcaster.
        AtomicInteger callbacks = new AtomicInteger();
        assertFalse(OptionVoteManager.start(unavailableWorld, "audit", "", "", List.of(new VoteOption("a", "A")), 10,
                result -> callbacks.incrementAndGet()));
        assertNull(OptionVoteManager.currentVoteId(unavailableWorld));
        assertEquals(0, callbacks.get());
    }

    private static ItemStack grant(Player player, TaskInstance task) {
        ItemStack item = ItemReclaimHelper.tagGrantedItem(new ItemStack(Items.DIAMOND_SWORD), task.getFullId());
        player.getInventory().items.set(0, item);
        return item;
    }
    private static Player player() throws Exception {
        var player = allocate(net.minecraft.client.player.RemotePlayer.class);
        player.setUUID(UUID.randomUUID());
        var inventoryField = Arrays.stream(Player.class.getDeclaredFields())
                .filter(field -> field.getType() == Inventory.class).findFirst().orElseThrow();
        inventoryField.setAccessible(true);
        inventoryField.set(player, new Inventory(player));
        return player;
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
}
