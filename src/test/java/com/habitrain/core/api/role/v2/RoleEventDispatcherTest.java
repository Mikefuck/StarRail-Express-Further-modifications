package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.v2.behavior.Decision;
import com.habitrain.core.api.role.v2.behavior.RoleCombatHooks;
import com.habitrain.core.api.role.v2.behavior.RoleHookContext;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleInteractionHooks;
import com.habitrain.core.api.role.v2.behavior.RoleLifecycleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleMeetingHooks;
import com.habitrain.core.api.role.v2.behavior.RoleScope;
import com.habitrain.core.api.role.v2.behavior.RoleShopHooks;
import com.habitrain.core.api.role.v2.behavior.RoleTaskHooks;
import com.habitrain.core.api.role.v2.behavior.RoleWinHooks;
import com.habitrain.core.api.role.v2.behavior.WinOutcome;
import com.habitrain.core.api.role.v2.behavior.WinPatch;
import com.habitrain.core.api.role.v2.behavior.WinPatchOp;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import io.wifi.starrailexpress.util.ShopEntry;
import com.habitrain.core.role.behavior.HookType;
import com.habitrain.core.role.behavior.RoleEventDispatcher;
import com.habitrain.core.role.behavior.RoleHookRegistry;
import com.habitrain.core.role.behavior.RoleScopeEvaluator;
import com.habitrain.core.role.diag.RoleDiagnosticsCommands;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 central event dispatcher: {@link Decision} merge policy,
 * {@link RoleHookRegistry} per-provider entry collection, dispatch isolation (a
 * throwing hook never propagates) and the circuit breaker (repeated failures
 * disable a hook).
 *
 * <p>The dispatch methods take the {@link RoleKey} directly and the hook
 * signatures accept a nullable player, so the pure dispatch logic is exercised
 * without a launched game. Registry/dispatcher state is reset via reflection.
 */
class RoleEventDispatcherTest {

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");
    private static final ResourceLocation DEATH = ResourceLocation.parse("sre:gun");

    @BeforeEach
    void reset() throws Exception {
        setField(RoleHookRegistry.class, RoleHookRegistry.INSTANCE, "hooks", new LinkedHashMap<>());
        setField(RoleHookRegistry.class, RoleHookRegistry.INSTANCE, "frozen", false);
        setField(RoleEventDispatcher.class, RoleEventDispatcher.INSTANCE, "circuits", new HashMap<>());
        setField(RoleEventDispatcher.class, RoleEventDispatcher.INSTANCE, "hookGates", RoleScopeEvaluator.LENIENT);
        setField(RoleEventDispatcher.class, RoleEventDispatcher.INSTANCE, "lastSeenSnapshot", null);
        setField(RoleEventDispatcher.class, RoleEventDispatcher.INSTANCE, "snapshotProvider",
                (Supplier<RoleSnapshotId>) () -> new RoleSnapshotId(0));
    }

    // ------------------------------------------------------------------
    // Decision merge policy
    // ------------------------------------------------------------------

    @Test
    void decisionMergeDenyDominates() {
        assertEquals(Decision.DENY, Decision.merge(Decision.ALLOW, Decision.DENY));
        assertEquals(Decision.DENY, Decision.merge(Decision.DENY, Decision.PASS));
        assertEquals(Decision.DENY, Decision.merge(Decision.DENY, Decision.DENY));
    }

    @Test
    void decisionMergeAllowBeatsPass() {
        assertEquals(Decision.ALLOW, Decision.merge(Decision.PASS, Decision.ALLOW));
        assertEquals(Decision.ALLOW, Decision.merge(Decision.ALLOW, Decision.PASS));
        assertEquals(Decision.PASS, Decision.merge(Decision.PASS, Decision.PASS));
    }

    // ------------------------------------------------------------------
    // RoleHookRegistry per-provider entry collection
    // ------------------------------------------------------------------

    @Test
    void registryStoresAndReturnsHooks() {
        RoleHooks hooks = RoleHooks.builder().combat(noopCombat()).build();
        RoleHookRegistry.INSTANCE.register(ROLE, hooks);
        assertTrue(RoleHookRegistry.INSTANCE.hasHooks(ROLE));
        RoleHooks stored = RoleHookRegistry.INSTANCE.get(ROLE);
        assertSame(hooks.combat(), stored.combat(), "callback instance preserved through decomposition");
    }

    @Test
    void registryKeepsCategoriesAcrossRegistrations() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder().combat(noopCombat()).build());
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder().lifecycle(noopLifecycle()).build());
        RoleHooks merged = RoleHookRegistry.INSTANCE.get(ROLE);
        assertTrue(merged.combat() != null, "combat category preserved");
        assertTrue(merged.lifecycle() != null, "lifecycle category kept");
    }

    @Test
    void registryKeepsTwoProvidersCombatEntriesSeparately() {
        // Two providers hooking the same category for the same role are NOT merged
        // away: each keeps its own entry and both must execute (fix-doc §9.1).
        RoleHookRegistry.INSTANCE.register(ROLE, RoleScope.HOLDER, "provider_a", "entry_a",
                PatchPriority.NORMAL, RoleHooks.builder().combat(noopCombat()).build());
        RoleHookRegistry.INSTANCE.register(ROLE, RoleScope.HOLDER, "provider_b", "entry_b",
                PatchPriority.NORMAL, RoleHooks.builder().combat(noopCombat()).build());
        assertEquals(2, RoleHookRegistry.INSTANCE.entries(ROLE, HookType.COMBAT_ON_DEATH).size(),
                "each provider's combat callback stays its own ordered entry");
    }

    @Test
    void registryRejectsRegistrationAfterFreeze() {
        RoleHookRegistry.INSTANCE.freeze();
        assertThrows(IllegalStateException.class,
                () -> RoleHookRegistry.INSTANCE.register(ROLE,
                        RoleHooks.builder().combat(noopCombat()).build()));
    }

    // ------------------------------------------------------------------
    // Dispatch isolation + Decision result
    // ------------------------------------------------------------------

    @Test
    void allowDeathDenyIsReturned() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public Decision allowDeath(ServerPlayer player, ResourceLocation deathReason,
                                               RoleHookContext ctx) {
                        return Decision.DENY;
                    }
                }).build());
        assertEquals(Decision.DENY,
                RoleEventDispatcher.INSTANCE.dispatchAllowDeath(ROLE, null, DEATH));
    }

    @Test
    void throwingHookIsIsolatedAndFallsBackToPass() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public Decision allowDeath(ServerPlayer player, ResourceLocation deathReason,
                                               RoleHookContext ctx) {
                        throw new IllegalStateException("boom");
                    }
                }).build());
        assertEquals(Decision.PASS,
                RoleEventDispatcher.INSTANCE.dispatchAllowDeath(ROLE, null, DEATH),
                "a throwing hook must not propagate and must fall back to PASS");
    }

    @Test
    void throwingNotificationHookDoesNotPropagate() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onDeath(ServerPlayer player, ResourceLocation deathReason,
                                        RoleHookContext ctx) {
                        throw new IllegalStateException("boom");
                    }
                }).build());
        // Must not throw.
        RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);
    }

    @Test
    void noHooksReturnsPass() {
        assertEquals(Decision.PASS,
                RoleEventDispatcher.INSTANCE.dispatchAllowDeath(ROLE, null, DEATH));
        assertNull(RoleHookRegistry.INSTANCE.get(ROLE));
    }

    // ------------------------------------------------------------------
    // Circuit breaker
    // ------------------------------------------------------------------

    @Test
    void circuitBreakerDisablesHookAfterRepeatedFailures() {
        AtomicInteger calls = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onDeath(ServerPlayer player, ResourceLocation deathReason,
                                        RoleHookContext ctx) {
                        calls.incrementAndGet();
                        throw new IllegalStateException("boom");
                    }
                }).build());

        // Threshold is 5; the 6th call is skipped by the broken circuit.
        for (int i = 0; i < 10; i++) {
            RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);
        }
        assertEquals(5, calls.get(), "hook must be circuit-broken after 5 consecutive failures");
        List<com.habitrain.core.api.role.v2.HookPerfEntry> rows =
                RoleEventDispatcher.INSTANCE.perf();
        assertEquals(1, rows.size());
        assertTrue(rows.getFirst().broken());
        assertEquals(5, rows.getFirst().failures());
        assertTrue(rows.getFirst().invocations() >= 5);
        List<String> lines = RoleDiagnosticsCommands.perf();
        assertTrue(lines.getFirst().startsWith("perf "));
        assertTrue(lines.stream().anyMatch(l -> l.contains("BROKEN")));
    }

    @Test
    void resetCircuitsAllowsBrokenHookAgain() {
        AtomicInteger calls = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onDeath(ServerPlayer player, ResourceLocation deathReason,
                                        RoleHookContext ctx) {
                        calls.incrementAndGet();
                        throw new IllegalStateException("boom");
                    }
                }).build());
        for (int i = 0; i < 6; i++) {
            RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);
        }
        assertEquals(5, calls.get());
        RoleEventDispatcher.INSTANCE.resetCircuits();
        RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);
        assertEquals(6, calls.get(), "round-start reset must re-enable a broken hook");
    }

    @Test
    void dispatchOnLostInvokesLifecycleHook() {
        AtomicInteger hits = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .lifecycle(new RoleLifecycleHooks() {
                    @Override
                    public void onLost(ServerPlayer player, RoleHookContext ctx) {
                        hits.incrementAndGet();
                        assertEquals(ROLE, ctx.role());
                        assertNull(ctx.server(), "null player must keep a null server");
                    }
                }).build());
        RoleEventDispatcher.INSTANCE.dispatchOnLost(ROLE, null);
        assertEquals(1, hits.get());
    }

    @Test
    void dispatchOnKillUsesTheSuppliedRoleKey() {
        AtomicInteger hits = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onKill(ServerPlayer victim, ServerPlayer killer,
                                       ResourceLocation deathReason, RoleHookContext ctx) {
                        hits.incrementAndGet();
                        assertEquals(ROLE, ctx.role());
                    }
                }).build());
        RoleEventDispatcher.INSTANCE.dispatchOnKill(ROLE, null, null, DEATH);
        assertEquals(1, hits.get());
    }

    @Test
    void allowDeathByKillerDenyIsReturned() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public Decision allowDeathByKiller(ServerPlayer victim, ServerPlayer killer,
                                                       ResourceLocation deathReason, RoleHookContext ctx) {
                        return Decision.DENY;
                    }
                }).build());
        assertEquals(Decision.DENY,
                RoleEventDispatcher.INSTANCE.dispatchAllowDeathByKiller(ROLE, null, null, DEATH));
    }

    @Test
    void onAnyDeathBroadcastsToEverySubscribedRole() {
        RoleKey other = RoleKey.of("habitrain_core", "other_role");
        AtomicInteger hits = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onAnyDeath(ServerPlayer dead, ResourceLocation deathReason, RoleHookContext ctx) {
                        hits.incrementAndGet();
                    }
                }).build());
        RoleHookRegistry.INSTANCE.register(other, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onAnyDeath(ServerPlayer dead, ResourceLocation deathReason, RoleHookContext ctx) {
                        hits.incrementAndGet();
                    }
                }).build());
        RoleEventDispatcher.INSTANCE.dispatchOnAnyDeath(null, DEATH);
        assertEquals(2, hits.get());
    }

    @Test
    void dispatchOnDeathWithBodyInvokesCombatHook() {
        AtomicInteger hits = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onDeathWithBody(ServerPlayer victim, ServerPlayer killer,
                                                ResourceLocation deathReason,
                                                io.wifi.starrailexpress.content.entity.PlayerBodyEntity body,
                                                RoleHookContext ctx) {
                        hits.incrementAndGet();
                        assertEquals(ROLE, ctx.role());
                    }
                }).build());
        RoleEventDispatcher.INSTANCE.dispatchOnDeathWithBody(ROLE, null, null, DEATH, null);
        assertEquals(1, hits.get());
    }

    @Test
    void useItemConsumeIsReturned() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .interaction(new RoleInteractionHooks() {
                    @Override
                    public InteractionResult useItem(ServerPlayer player, ItemStack stack,
                                                     InteractionHand hand, RoleHookContext ctx) {
                        return InteractionResult.SUCCESS;
                    }
                }).build());
        assertEquals(InteractionResult.SUCCESS,
                RoleEventDispatcher.INSTANCE.dispatchUseItem(ROLE, null, null, InteractionHand.MAIN_HAND));
    }

    @Test
    void useItemStopsAtFirstTerminalResult() {
        AtomicInteger secondHits = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleScope.HOLDER, "first_provider", "first_entry",
                PatchPriority.NORMAL, RoleHooks.builder().interaction(new RoleInteractionHooks() {
                    @Override
                    public InteractionResult useItem(ServerPlayer player, ItemStack stack,
                                                     InteractionHand hand, RoleHookContext ctx) {
                        return InteractionResult.CONSUME;
                    }
                }).build());
        RoleHookRegistry.INSTANCE.register(ROLE, RoleScope.HOLDER, "second_provider", "second_entry",
                PatchPriority.NORMAL, RoleHooks.builder().interaction(new RoleInteractionHooks() {
                    @Override
                    public InteractionResult useItem(ServerPlayer player, ItemStack stack,
                                                     InteractionHand hand, RoleHookContext ctx) {
                        secondHits.incrementAndGet();
                        return InteractionResult.FAIL;
                    }
                }).build());

        assertEquals(InteractionResult.CONSUME,
                RoleEventDispatcher.INSTANCE.dispatchUseItem(ROLE, null, null, InteractionHand.MAIN_HAND));
        assertEquals(0, secondHits.get(), "handlers after the first terminal result must not run");
    }

    @Test
    void registryMergesInteractionCategory() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder().combat(noopCombat()).build());
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .interaction(new RoleInteractionHooks() {}).build());
        RoleHooks merged = RoleHookRegistry.INSTANCE.get(ROLE);
        assertTrue(merged.combat() != null);
        assertTrue(merged.interaction() != null);
    }

    @Test
    void registryMergesShopTaskMeetingWinCategories() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder().shop(new RoleShopHooks() {}).build());
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder().task(new RoleTaskHooks() {}).build());
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder().meeting(new RoleMeetingHooks() {}).build());
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder().win(new RoleWinHooks() {}).build());
        RoleHooks merged = RoleHookRegistry.INSTANCE.get(ROLE);
        assertTrue(merged.shop() != null);
        assertTrue(merged.task() != null);
        assertTrue(merged.meeting() != null);
        assertTrue(merged.win() != null);
    }

    @Test
    void gateBuyWithNoRoleReturnsPass() {
        assertEquals(Decision.PASS, RoleEventDispatcher.INSTANCE.gateBuy(null, null, 0, 0));
    }

    @Test
    void allowBuyDenyIsReturned() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .shop(new RoleShopHooks() {
                    @Override
                    public Decision allowBuy(ServerPlayer buyer, ShopEntry entry,
                                             int index, int price, RoleHookContext ctx) {
                        return Decision.DENY;
                    }
                }).build());
        assertEquals(Decision.DENY,
                RoleEventDispatcher.INSTANCE.dispatchAllowBuy(ROLE, null, null, 0, 50));
    }

    @Test
    void onBuyInvokesShopHook() {
        AtomicInteger hits = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .shop(new RoleShopHooks() {
                    @Override
                    public void onBuy(ServerPlayer buyer, ShopEntry entry,
                                      int index, int price, RoleHookContext ctx) {
                        hits.incrementAndGet();
                        assertEquals(3, index);
                        assertEquals(80, price);
                    }
                }).build());
        RoleEventDispatcher.INSTANCE.dispatchOnBuy(ROLE, null, null, 3, 80);
        assertEquals(1, hits.get());
    }

    @Test
    void onAnyBuyBroadcastsToEverySubscribedRole() {
        RoleKey other = RoleKey.of("habitrain_core", "shop_observer");
        AtomicInteger hits = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .shop(new RoleShopHooks() {
                    @Override
                    public void onAnyBuy(ServerPlayer buyer, ShopEntry entry,
                                         int index, int price, RoleHookContext ctx) {
                        hits.incrementAndGet();
                    }
                }).build());
        RoleHookRegistry.INSTANCE.register(other, RoleHooks.builder()
                .shop(new RoleShopHooks() {
                    @Override
                    public void onAnyBuy(ServerPlayer buyer, ShopEntry entry,
                                         int index, int price, RoleHookContext ctx) {
                        hits.incrementAndGet();
                    }
                }).build());
        RoleEventDispatcher.INSTANCE.dispatchOnAnyBuy(null, null, 0, 10);
        assertEquals(2, hits.get());
    }

    @Test
    void onFinishQuestInvokesTaskHook() {
        AtomicReference<String> seen = new AtomicReference<>();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .task(new RoleTaskHooks() {
                    @Override
                    public void onFinishQuest(ServerPlayer player, String quest,
                                              int taskStreak, boolean parallel, RoleHookContext ctx) {
                        seen.set(quest);
                        assertEquals(2, taskStreak);
                        assertTrue(parallel);
                    }
                }).build());
        RoleEventDispatcher.INSTANCE.dispatchOnFinishQuest(ROLE, null, "habitrain_core:add_coal", 2, true);
        assertEquals("habitrain_core:add_coal", seen.get());
    }

    @Test
    void meetingStartBroadcastsToEverySubscribedRole() {
        RoleKey other = RoleKey.of("habitrain_core", "meeting_role");
        AtomicInteger hits = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .meeting(new RoleMeetingHooks() {
                    @Override
                    public void onMeetingStart(net.minecraft.server.level.ServerLevel level,
                                               ServerPlayer reporter, RoleHookContext ctx) {
                        hits.incrementAndGet();
                    }
                }).build());
        RoleHookRegistry.INSTANCE.register(other, RoleHooks.builder()
                .meeting(new RoleMeetingHooks() {
                    @Override
                    public void onMeetingStart(net.minecraft.server.level.ServerLevel level,
                                               ServerPlayer reporter, RoleHookContext ctx) {
                        hits.incrementAndGet();
                    }
                }).build());
        RoleEventDispatcher.INSTANCE.dispatchOnMeetingStart(null, null);
        assertEquals(2, hits.get());
    }

    @Test
    void allowVoteOutDenyIsReturned() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .meeting(new RoleMeetingHooks() {
                    @Override
                    public Decision allowVoteOut(ServerPlayer voted, RoleHookContext ctx) {
                        return Decision.DENY;
                    }
                }).build());
        assertEquals(Decision.DENY,
                RoleEventDispatcher.INSTANCE.dispatchAllowVoteOut(ROLE, null));
    }

    @Test
    void allowGameEndDenyFoldsAcrossRoles() {
        RoleKey other = RoleKey.of("habitrain_core", "pride_like");
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .win(new RoleWinHooks() {
                    @Override
                    public Decision allowGameEnd(net.minecraft.server.level.ServerLevel level,
                                                 String proposed, boolean loose, RoleHookContext ctx) {
                        return Decision.PASS;
                    }
                }).build());
        RoleHookRegistry.INSTANCE.register(other, RoleHooks.builder()
                .win(new RoleWinHooks() {
                    @Override
                    public Decision allowGameEnd(net.minecraft.server.level.ServerLevel level,
                                                 String proposed, boolean loose, RoleHookContext ctx) {
                        return Decision.DENY;
                    }
                }).build());
        assertEquals(Decision.DENY,
                RoleEventDispatcher.INSTANCE.dispatchAllowGameEnd(null, "KILLERS", false));
    }

    @Test
    void evaluateWinFoldsAddThenReplace() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        RoleKey other = RoleKey.of("habitrain_core", "win_role");
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .win(new RoleWinHooks() {
                    @Override
                    public WinPatch evaluateWin(net.minecraft.server.level.ServerLevel level,
                                                String proposed, boolean loose, RoleHookContext ctx) {
                        return WinPatch.addWinners(a);
                    }
                }).build());
        RoleHookRegistry.INSTANCE.register(other, RoleHooks.builder()
                .win(new RoleWinHooks() {
                    @Override
                    public WinPatch evaluateWin(net.minecraft.server.level.ServerLevel level,
                                                String proposed, boolean loose, RoleHookContext ctx) {
                        return WinPatch.replaceWinners(List.of(b));
                    }
                }).build());
        WinPatch folded = RoleEventDispatcher.INSTANCE.dispatchEvaluateWin(null, "BLACKOUT", false);
        assertEquals(WinPatchOp.REPLACE_WINNERS, folded.op());
        assertEquals(List.of(b), folded.winners());
    }

    @Test
    void afterWinnersFinalizedBroadcasts() {
        AtomicInteger hits = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .win(new RoleWinHooks() {
                    @Override
                    public void afterWinnersFinalized(net.minecraft.server.level.ServerLevel level,
                                                      WinOutcome outcome, RoleHookContext ctx) {
                        hits.incrementAndGet();
                        assertEquals("CUSTOM", outcome.status());
                    }
                }).build());
        RoleEventDispatcher.INSTANCE.dispatchAfterWinnersFinalized(
                null, new WinOutcome("CUSTOM", List.of(), "test"));
        assertEquals(1, hits.get());
    }

    @Test
    void checkBlackoutWinReturnsNullWhenNoPatch() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .win(new RoleWinHooks() {}).build());
        assertNull(RoleEventDispatcher.INSTANCE.checkBlackoutWin(null));
    }

    @Test
    void checkBlackoutWinReturnsCustomResult() {
        UUID winner = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .win(new RoleWinHooks() {
                    @Override
                    public WinPatch evaluateWin(net.minecraft.server.level.ServerLevel level,
                                                String proposed, boolean loose, RoleHookContext ctx) {
                        return WinPatch.declareCustom("habitrain_core:test_role",
                                List.of(winner), "custom win");
                    }
                }).build());
        WinResult result = RoleEventDispatcher.INSTANCE.checkBlackoutWin(null);
        assertEquals(List.of(winner), result.getWinners());
        assertEquals("custom win", result.getReason());
    }

    @Test
    void winPatchMergeAddThenRemove() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        WinPatch acc = WinPatch.merge(WinPatch.noChange(), WinPatch.addWinners(a, b));
        acc = WinPatch.merge(acc, WinPatch.removeWinners(a));
        assertEquals(WinPatchOp.ADD_WINNER, acc.op());
        assertEquals(List.of(b), acc.winners());
    }

    @Test
    void winPatchPureRemoveFromNoChangeIsNoop() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        WinPatch acc = WinPatch.merge(WinPatch.noChange(), WinPatch.removeWinners(a));
        // A REMOVE on an empty accumulator must not yield a REMOVE_WINNER patch:
        // the dispatcher would misread that patch's winners as "declare winners".
        assertEquals(WinPatchOp.NO_CHANGE, acc.op());
        assertEquals(List.of(), acc.winners());
    }

    @Test
    void winPatchDeclareOverwritesAccumulator() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        WinPatch acc = WinPatch.merge(WinPatch.addWinners(a), WinPatch.declareFaction("KILLERS"));
        assertEquals(WinPatchOp.DECLARE_FACTION_WIN, acc.op());
        assertEquals("KILLERS", acc.faction());
    }

    @Test
    void successResetsFailureCounter() {
        AtomicInteger calls = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onDeath(ServerPlayer player, ResourceLocation deathReason,
                                        RoleHookContext ctx) {
                        calls.incrementAndGet();
                        if (calls.get() % 2 == 0) {
                            throw new IllegalStateException("boom");
                        }
                    }
                }).build());

        // Alternating success/failure: the counter resets on success, so the hook
        // never accumulates 5 consecutive failures and is never broken.
        for (int i = 0; i < 20; i++) {
            RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);
        }
        assertEquals(20, calls.get(), "success must reset the consecutive-failure counter");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static RoleCombatHooks noopCombat() {
        return new RoleCombatHooks() {};
    }

    private static RoleLifecycleHooks noopLifecycle() {
        return new RoleLifecycleHooks() {};
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
