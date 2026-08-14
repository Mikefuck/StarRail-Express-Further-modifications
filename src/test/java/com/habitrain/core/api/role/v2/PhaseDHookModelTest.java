package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.behavior.Decision;
import com.habitrain.core.api.role.v2.behavior.RoleCombatHooks;
import com.habitrain.core.api.role.v2.behavior.RoleHookContext;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleScope;
import com.habitrain.core.api.role.v2.behavior.RoleWinHooks;
import com.habitrain.core.api.role.v2.behavior.WinPatch;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.role.behavior.HookGates;
import com.habitrain.core.role.behavior.HookType;
import com.habitrain.core.role.behavior.RoleEventDispatcher;
import com.habitrain.core.role.behavior.RoleHookRegistry;
import com.habitrain.core.role.behavior.RoleScopeEvaluator;
import com.habitrain.core.role.behavior.WinFoldResult;
import com.habitrain.core.role.extension.ProviderRegistrationTransaction;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D: managed-hook model. Multiple providers hooking the same category for
 * the same role each keep their own ordered entry (fix-doc §9.1), broadcast hooks
 * are gated by {@link RoleScope} against the round (fix-doc §9.2), the breaker is
 * per provider/entry slot (fix-doc §9.4) and the standard/blackout victory chains
 * share one {@link RoleEventDispatcher#foldWin} fold (fix-doc §9.3/§9.5).
 */
class PhaseDHookModelTest {

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");
    private static final RoleKey OTHER = RoleKey.of("habitrain_core", "other_role");
    private static final ResourceLocation DEATH = ResourceLocation.parse("sre:gun");

    @BeforeEach
    void reset() throws Exception {
        setField(RoleHookRegistry.class, RoleHookRegistry.INSTANCE, "hooks", new LinkedHashMap<>());
        setField(RoleHookRegistry.class, RoleHookRegistry.INSTANCE, "frozen", false);
        // The registry singleton is shared across test classes; a prior class
        // that froze (or registered an ADD) would otherwise leak frozen /
        // tmmAccessible and break begin()/commit() here.
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "frozen", false);
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "tmmAccessible", false);
        setField(RoleEventDispatcher.class, RoleEventDispatcher.INSTANCE, "circuits", new HashMap<>());
        setField(RoleEventDispatcher.class, RoleEventDispatcher.INSTANCE, "hookGates", RoleScopeEvaluator.LENIENT);
        setField(RoleEventDispatcher.class, RoleEventDispatcher.INSTANCE, "lastSeenSnapshot", null);
        setField(RoleEventDispatcher.class, RoleEventDispatcher.INSTANCE, "snapshotProvider",
                (Supplier<RoleSnapshotId>) () -> new RoleSnapshotId(0));
    }

    // ------------------------------------------------------------------
    // Stable per-provider ordering
    // ------------------------------------------------------------------

    @Test
    void twoProvidersSameCategoryRunInStableOrder() {
        List<String> order = new ArrayList<>();
        registerCombat(ROLE, "provider_aa", "first", PatchPriority.EARLY, onDeathOrder(order, "first"));
        registerCombat(ROLE, "provider_bb", "second", PatchPriority.NORMAL, onDeathOrder(order, "second"));
        registerCombat(ROLE, "provider_cc", "third", PatchPriority.LATE, onDeathOrder(order, "third"));

        RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);

        assertEquals(List.of("first", "second", "third"), order,
                "two providers for the same category must both execute, ordered by priority then provider");
    }

    @Test
    void samePriorityOrdersByProviderId() {
        List<String> order = new ArrayList<>();
        registerCombat(ROLE, "provider_zz", "z", PatchPriority.NORMAL, onDeathOrder(order, "z"));
        registerCombat(ROLE, "provider_aa", "a", PatchPriority.NORMAL, onDeathOrder(order, "a"));

        RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);

        assertEquals(List.of("a", "z"), order, "same priority breaks ties by provider id");
    }

    // ------------------------------------------------------------------
    // Scope gating (fix-doc §9.2)
    // ------------------------------------------------------------------

    @Test
    void absentRoleBroadcastHookIsGated() {
        AtomicInteger roled = new AtomicInteger();
        AtomicInteger othered = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onAnyDeath(ServerPlayer dead, ResourceLocation reason, RoleHookContext ctx) {
                        roled.incrementAndGet();
                    }
                }).build());
        RoleHookRegistry.INSTANCE.register(OTHER, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onAnyDeath(ServerPlayer dead, ResourceLocation reason, RoleHookContext ctx) {
                        othered.incrementAndGet();
                    }
                }).build());

        // OTHER has no active holder → its broadcast hook must be skipped.
        RoleEventDispatcher.INSTANCE.setHookGates(new HookGates() {
            @Override
            public boolean activeHolder(RoleKey role, @Nullable ServerLevel level) {
                return role.equals(ROLE);
            }

            @Override
            public boolean presentInRound(RoleKey role, @Nullable ServerLevel level) {
                return role.equals(ROLE);
            }
        });

        RoleEventDispatcher.INSTANCE.dispatchOnAnyDeath(null, DEATH);
        assertEquals(1, roled.get(), "role with a holder fires");
        assertEquals(0, othered.get(), "role without a holder is skipped");
    }

    @Test
    void roundPresentGateSkipsAbsentRole() {
        AtomicInteger hits = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleScope.ROUND_PRESENT, "provider_x", "entry_present",
                PatchPriority.NORMAL, RoleHooks.builder()
                        .combat(new RoleCombatHooks() {
                            @Override
                            public void onAnyDeath(ServerPlayer dead, ResourceLocation reason, RoleHookContext ctx) {
                                hits.incrementAndGet();
                            }
                        }).build());

        RoleEventDispatcher.INSTANCE.setHookGates(new HookGates() {
            @Override
            public boolean activeHolder(RoleKey role, @Nullable ServerLevel level) {
                return true;
            }

            @Override
            public boolean presentInRound(RoleKey role, @Nullable ServerLevel level) {
                return false; // role absent from the round snapshot
            }
        });

        RoleEventDispatcher.INSTANCE.dispatchOnAnyDeath(null, DEATH);
        assertEquals(0, hits.get(), "ROUND_PRESENT hook must not fire for a role absent from the round");
    }

    // ------------------------------------------------------------------
    // Per-provider circuit breaker (fix-doc §9.4)
    // ------------------------------------------------------------------

    @Test
    void circuitBreakerIsolatedPerProviderEntry() {
        AtomicInteger brokenCalls = new AtomicInteger();
        AtomicInteger healthyCalls = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleScope.HOLDER, "provider_broken", "entry_b",
                PatchPriority.NORMAL, RoleHooks.builder()
                        .combat(new RoleCombatHooks() {
                            @Override
                            public void onDeath(ServerPlayer player, ResourceLocation reason, RoleHookContext ctx) {
                                brokenCalls.incrementAndGet();
                                throw new IllegalStateException("boom");
                            }
                        }).build());
        RoleHookRegistry.INSTANCE.register(ROLE, RoleScope.HOLDER, "provider_healthy", "entry_h",
                PatchPriority.NORMAL, RoleHooks.builder()
                        .combat(new RoleCombatHooks() {
                            @Override
                            public void onDeath(ServerPlayer player, ResourceLocation reason, RoleHookContext ctx) {
                                healthyCalls.incrementAndGet();
                            }
                        }).build());

        for (int i = 0; i < 6; i++) {
            RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);
        }
        assertEquals(5, brokenCalls.get(), "broken provider tripped after 5 consecutive failures");
        assertEquals(6, healthyCalls.get(), "healthy provider's own slot must keep running");
    }

    @Test
    void snapshotChangeClearsBreaker() {
        AtomicInteger calls = new AtomicInteger();
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onDeath(ServerPlayer player, ResourceLocation reason, RoleHookContext ctx) {
                        calls.incrementAndGet();
                        throw new IllegalStateException("boom");
                    }
                }).build());

        AtomicInteger snapVersion = new AtomicInteger(1);
        RoleEventDispatcher.INSTANCE.setSnapshotProvider(() -> new RoleSnapshotId(snapVersion.get()));

        for (int i = 0; i < 5; i++) {
            RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);
        }
        assertEquals(5, calls.get(), "breaker tripped");

        snapVersion.set(2); // new snapshot → breakers reset
        RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);
        assertEquals(6, calls.get(), "a new snapshot must clear the breaker so the hook runs again");
    }

    @Test
    void perfCarriesProviderAndEntry() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleScope.HOLDER, "provider_x", "entry_y",
                PatchPriority.NORMAL, RoleHooks.builder()
                        .combat(new RoleCombatHooks() {
                            @Override
                            public void onDeath(ServerPlayer player, ResourceLocation reason, RoleHookContext ctx) {
                                // no-op, just record the slot
                            }
                        }).build());

        RoleEventDispatcher.INSTANCE.dispatchOnDeath(ROLE, null, DEATH);

        List<HookPerfEntry> rows = RoleEventDispatcher.INSTANCE.perf();
        assertEquals(1, rows.size());
        assertEquals("provider_x", rows.getFirst().providerId());
        assertEquals("entry_y", rows.getFirst().entryId());
    }

    // ------------------------------------------------------------------
    // Unified victory fold (fix-doc §9.3/§9.5)
    // ------------------------------------------------------------------

    @Test
    void foldWinReportsBothGateAndPatch() {
        UUID winner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .win(new RoleWinHooks() {
                    @Override
                    public Decision allowGameEnd(ServerLevel level, String proposed,
                                                 boolean loose, RoleHookContext ctx) {
                        return Decision.DENY;
                    }

                    @Override
                    public WinPatch evaluateWin(ServerLevel level, String proposed,
                                                boolean loose, RoleHookContext ctx) {
                        return WinPatch.declareCustom("habitrain_core:test_role",
                                List.of(winner), "custom win");
                    }
                }).build());

        WinFoldResult fold = RoleEventDispatcher.INSTANCE.foldWin(null, "KILLERS", false);
        assertTrue(fold.denied(), "gate DENY is surfaced");
        assertTrue(fold.hasPatch(), "winner patch is surfaced alongside the gate");
        assertEquals(List.of(winner), fold.toWinResult().getWinners());
        assertEquals("custom win", fold.toWinResult().getReason());
    }

    @Test
    void foldWinNoPatchWhenHooksStaySilent() {
        RoleHookRegistry.INSTANCE.register(ROLE, RoleHooks.builder()
                .win(new RoleWinHooks() {}).build());
        WinFoldResult fold = RoleEventDispatcher.INSTANCE.foldWin(null, "KILLERS", false);
        assertFalse(fold.denied());
        assertFalse(fold.hasPatch());
    }

    // ------------------------------------------------------------------
    // Provider transaction staging
    // ------------------------------------------------------------------

    @Test
    void providerTransactionHooksCommitUnderProviderId() {
        ProviderRegistrationTransaction tx = RoleExtensionRegistry.INSTANCE.begin("provider_x");
        tx.hooks(ROLE, RoleScope.HOLDER, RoleHooks.builder().combat(noopCombat()).build());
        tx.commit();

        List<com.habitrain.core.role.behavior.ManagedHookEntry> entries =
                RoleHookRegistry.INSTANCE.entries(ROLE, HookType.COMBAT_ON_DEATH);
        assertEquals(1, entries.size());
        assertEquals("provider_x", entries.getFirst().providerId());
        assertTrue(entries.getFirst().entryId().startsWith("provider_x$hooks:"),
                "entry id is provider-owned");
    }

    @Test
    void providerTransactionRollbackDropsHooks() {
        ProviderRegistrationTransaction tx = RoleExtensionRegistry.INSTANCE.begin("provider_y");
        tx.hooks(ROLE, RoleScope.HOLDER, RoleHooks.builder().combat(noopCombat()).build());
        tx.rollback();

        assertTrue(RoleHookRegistry.INSTANCE.entries(ROLE, HookType.COMBAT_ON_DEATH).isEmpty(),
                "rolled-back hooks must never reach the registry");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static RoleCombatHooks noopCombat() {
        return new RoleCombatHooks() {};
    }

    private static void registerCombat(RoleKey role, String provider, String entry,
                                       PatchPriority priority, RoleCombatHooks hooks) {
        RoleHookRegistry.INSTANCE.register(role, RoleScope.HOLDER, provider, entry, priority,
                RoleHooks.builder().combat(hooks).build());
    }

    private static RoleCombatHooks onDeathOrder(List<String> order, String label) {
        return new RoleCombatHooks() {
            @Override
            public void onDeath(ServerPlayer player, ResourceLocation reason, RoleHookContext ctx) {
                order.add(label);
            }
        };
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
