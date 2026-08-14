package com.habitrain.core.role.behavior;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshotId;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
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
import com.habitrain.core.api.role.v2.behavior.RoleTickHooks;
import com.habitrain.core.api.role.v2.behavior.RoleWinHooks;
import com.habitrain.core.api.role.v2.behavior.WinOutcome;
import com.habitrain.core.api.role.v2.behavior.WinPatch;
import com.habitrain.core.api.role.v2.behavior.WinPatchOp;
import com.habitrain.core.api.role.v2.state.ResetCause;
import com.habitrain.core.api.role.v2.state.RoleStateApi;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import io.wifi.starrailexpress.event.AllowGameEnd;
import io.wifi.starrailexpress.event.AllowPlayerDeath;
import io.wifi.starrailexpress.event.AllowPlayerDeathWithKiller;
import io.wifi.starrailexpress.event.MeetingEndEvent;
import io.wifi.starrailexpress.event.MeetingStartEvent;
import io.wifi.starrailexpress.event.MeetingVoteOutEvent;
import io.wifi.starrailexpress.event.OnDeathWithBody;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import io.wifi.starrailexpress.event.OnPlayerDeath;
import io.wifi.starrailexpress.event.OnPlayerDeathWithKiller;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.util.ShopEntry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.agmas.harpymodloader.events.ModdedRoleAssigned;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Central managed event dispatcher for the v2 role platform.
 *
 * <p>Core registers exactly one global listener per SRE/Fabric event; each
 * listener resolves the relevant player's current role and dispatches to that
 * role's managed {@link ManagedHookEntry}s. Providers never add permanent
 * listeners to the global bus.
 *
 * <p>Entries are ordered per {@code (role, hookType)} (fix-doc §8.1) and never
 * merged across providers, so two providers hooking the same category for the
 * same role both execute in stable order. Broadcast hooks are gated by their
 * registered {@link RoleScope} against the frozen round snapshot (fix-doc §9.2):
 * a role with no active holder / not in the round does not run restricted hooks.
 * Each {@code (role, hookType, provider, entry)} slot has its own circuit breaker,
 * cleared when the snapshot changes (fix-doc §9.4).
 */
public final class RoleEventDispatcher {

    public static final RoleEventDispatcher INSTANCE = new RoleEventDispatcher();
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleEventDispatcher");
    private static final int CIRCUIT_THRESHOLD = 5;
    /** Default per-hook budget (2 ms). Over-budget is recorded; it does not trip the breaker. */
    public static final long DEFAULT_BUDGET_NANOS = 2_000_000L;

    private final RoleHookRegistry registry = RoleHookRegistry.INSTANCE;
    private final Map<CircuitKey, HookCircuit> circuits = new HashMap<>();
    private volatile boolean listenersRegistered;
    private volatile Supplier<RoleSnapshotId> snapshotProvider = () -> new RoleSnapshotId(0);
    private volatile HookGates hookGates = RoleScopeEvaluator.LENIENT;
    private RoleSnapshotId lastSeenSnapshot;

    private RoleEventDispatcher() {}

    /** Overrides the snapshot source (used by core at runtime; defaults to a no-op for tests). */
    public void setSnapshotProvider(Supplier<RoleSnapshotId> provider) {
        this.snapshotProvider = provider;
    }

    /** Overrides the scope gate (core binds a snapshot-backed one at runtime). */
    public void setHookGates(@Nullable HookGates gates) {
        this.hookGates = gates == null ? RoleScopeEvaluator.LENIENT : gates;
    }

    // ------------------------------------------------------------------
    // Dispatch methods (testable: take the role directly)
    // ------------------------------------------------------------------

    /** Dispatches a death-gate decision; {@code DENY} prevents the death. */
    public Decision dispatchAllowDeath(RoleKey role, @Nullable ServerPlayer player,
                                       ResourceLocation deathReason) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.COMBAT_ALLOW_DEATH);
        Decision acc = Decision.PASS;
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), player);
            acc = Decision.merge(acc, invokeDecision(e, "allowDeath",
                    () -> ((RoleCombatHooks) e.callback()).allowDeath(player, deathReason, ctx)));
        }
        return acc;
    }

    /** Dispatches a death notification. */
    public void dispatchOnDeath(RoleKey role, @Nullable ServerPlayer player,
                                ResourceLocation deathReason) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.COMBAT_ON_DEATH);
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), player);
            invoke(e, "onDeath",
                    () -> ((RoleCombatHooks) e.callback()).onDeath(player, deathReason, ctx));
        }
    }

    /**
     * Dispatches a kill notification. Callers must pass the <em>killer</em>'s
     * role — {@code onKill} runs for the player who killed, and only after the
     * death has been confirmed.
     */
    public void dispatchOnKill(RoleKey role, @Nullable ServerPlayer victim,
                                @Nullable ServerPlayer killer,
                                @Nullable ResourceLocation deathReason) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.COMBAT_ON_KILL);
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), killer != null ? killer : victim);
            invoke(e, "onKill", () -> ((RoleCombatHooks) e.callback())
                    .onKill(victim, killer, deathReason, ctx));
        }
    }

    /**
     * Dispatches a killer-present death-gate; {@code DENY} prevents the death.
     * Runs for the <em>victim</em>'s role.
     */
    public Decision dispatchAllowDeathByKiller(RoleKey role, @Nullable ServerPlayer victim,
                                               @Nullable ServerPlayer killer,
                                               ResourceLocation deathReason) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.COMBAT_ALLOW_DEATH_BY_KILLER);
        Decision acc = Decision.PASS;
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), victim);
            acc = Decision.merge(acc, invokeDecision(e, "allowDeathByKiller",
                    () -> ((RoleCombatHooks) e.callback())
                            .allowDeathByKiller(victim, killer, deathReason, ctx)));
        }
        return acc;
    }

    /**
     * Broadcasts a death to every in-scope combat hook's {@code onAnyDeath}.
     * The dispatched role is the subscribed role, not the dead player's role.
     */
    public void dispatchOnAnyDeath(@Nullable ServerPlayer dead, ResourceLocation deathReason) {
        ServerLevel level = levelOf(dead);
        for (ManagedHookEntry e : registry.allEntries(HookType.COMBAT_ON_ANY_DEATH)) {
            if (!inScope(e, level)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), dead);
            invoke(e, "onAnyDeath",
                    () -> ((RoleCombatHooks) e.callback()).onAnyDeath(dead, deathReason, ctx));
        }
    }

    /**
     * Dispatches a corpse notification. Callers pass the role that should
     * observe the body (victim or killer).
     */
    public void dispatchOnDeathWithBody(RoleKey role, @Nullable ServerPlayer victim,
                                        @Nullable ServerPlayer killer,
                                        ResourceLocation deathReason,
                                        @Nullable PlayerBodyEntity body) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.COMBAT_ON_DEATH_WITH_BODY);
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), killer != null ? killer : victim);
            invoke(e, "onDeathWithBody", () -> ((RoleCombatHooks) e.callback())
                    .onDeathWithBody(victim, killer, deathReason, body, ctx));
        }
    }

    /**
     * Dispatches an item-use interaction. A non-{@code PASS} result consumes
     * the use (first consume wins when folding multiple entries).
     */
    public InteractionResult dispatchUseItem(RoleKey role, @Nullable ServerPlayer player,
                                             ItemStack stack, InteractionHand hand) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.INTERACTION_USE_ITEM);
        InteractionResult acc = InteractionResult.PASS;
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), player);
            InteractionResult next = invokeResult(e, "useItem",
                    () -> ((RoleInteractionHooks) e.callback()).useItem(player, stack, hand, ctx),
                    InteractionResult.PASS);
            if (next != InteractionResult.PASS) {
                return next;
            }
        }
        return acc;
    }

    /**
     * Dispatches a buy-gate decision for the buyer's role. {@code DENY}
     * cancels the purchase.
     */
    public Decision dispatchAllowBuy(RoleKey role, @Nullable ServerPlayer buyer,
                                     @Nullable ShopEntry entry, int index, int price) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.SHOP_ALLOW_BUY);
        Decision acc = Decision.PASS;
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), buyer);
            acc = Decision.merge(acc, invokeDecision(e, "allowBuy",
                    () -> ((RoleShopHooks) e.callback()).allowBuy(buyer, entry, index, price, ctx)));
        }
        return acc;
    }

    /** Dispatches a successful-buy notification for the buyer's role. */
    public void dispatchOnBuy(RoleKey role, @Nullable ServerPlayer buyer,
                              @Nullable ShopEntry entry, int index, int price) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.SHOP_ON_BUY);
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), buyer);
            invoke(e, "onBuy",
                    () -> ((RoleShopHooks) e.callback()).onBuy(buyer, entry, index, price, ctx));
        }
    }

    /**
     * Broadcasts a successful buy to every in-scope shop hook's {@code onAnyBuy}.
     */
    public void dispatchOnAnyBuy(@Nullable ServerPlayer buyer, @Nullable ShopEntry entry,
                                 int index, int price) {
        ServerLevel level = levelOf(buyer);
        for (ManagedHookEntry e : registry.allEntries(HookType.SHOP_ON_ANY_BUY)) {
            if (!inScope(e, level)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), buyer);
            invoke(e, "onAnyBuy",
                    () -> ((RoleShopHooks) e.callback()).onAnyBuy(buyer, entry, index, price, ctx));
        }
    }

    /**
     * Runtime entry used by the shop mixin. Resolves the buyer's role and
     * returns {@link Decision#DENY} when the purchase must be cancelled.
     */
    public Decision gateBuy(@Nullable Player buyer, @Nullable ShopEntry entry, int index, int price) {
        RoleKey role = currentRole(buyer);
        if (role == null) {
            return Decision.PASS;
        }
        return dispatchAllowBuy(role, asServer(buyer), entry, index, price);
    }

    /**
     * Runtime entry used by the shop mixin after a successful debit.
     * Fires the buyer's {@code onBuy} then broadcasts {@code onAnyBuy}.
     */
    public void notifyBuy(@Nullable Player buyer, @Nullable ShopEntry entry, int index, int price) {
        RoleKey role = currentRole(buyer);
        ServerPlayer serverBuyer = asServer(buyer);
        if (role != null) {
            dispatchOnBuy(role, serverBuyer, entry, index, price);
        }
        dispatchOnAnyBuy(serverBuyer, entry, index, price);
    }

    /** Dispatches a finish-quest notification for the holder's role. */
    public void dispatchOnFinishQuest(RoleKey role, @Nullable ServerPlayer player,
                                      @Nullable String quest, int taskStreak, boolean parallel) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.TASK_ON_FINISH_QUEST);
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), player);
            invoke(e, "onFinishQuest", () -> ((RoleTaskHooks) e.callback())
                    .onFinishQuest(player, quest, taskStreak, parallel, ctx));
        }
    }

    /**
     * Runtime entry used by the finish-quest mixin. No-ops when the player
     * has no current role.
     */
    public void notifyFinishQuest(@Nullable Player player, @Nullable String quest,
                                  int taskStreak, boolean parallel) {
        RoleKey role = currentRole(player);
        if (role == null) {
            return;
        }
        dispatchOnFinishQuest(role, asServer(player), quest, taskStreak, parallel);
    }

    /** Broadcasts a meeting-start notification to every in-scope role. */
    public void dispatchOnMeetingStart(@Nullable ServerLevel level, @Nullable ServerPlayer reporter) {
        for (ManagedHookEntry e : registry.allEntries(HookType.MEETING_ON_START)) {
            if (!inScope(e, level)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), level == null ? null : level.getServer());
            invoke(e, "onMeetingStart",
                    () -> ((RoleMeetingHooks) e.callback()).onMeetingStart(level, reporter, ctx));
        }
    }

    /** Broadcasts a meeting-end notification to every in-scope role. */
    public void dispatchOnMeetingEnd(@Nullable ServerLevel level) {
        for (ManagedHookEntry e : registry.allEntries(HookType.MEETING_ON_END)) {
            if (!inScope(e, level)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), level == null ? null : level.getServer());
            invoke(e, "onMeetingEnd",
                    () -> ((RoleMeetingHooks) e.callback()).onMeetingEnd(level, ctx));
        }
    }

    /**
     * Dispatches a vote-out gate for the voted player's role.
     * {@code DENY} blocks the ejection.
     */
    public Decision dispatchAllowVoteOut(RoleKey role, @Nullable ServerPlayer voted) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.MEETING_ALLOW_VOTE_OUT);
        Decision acc = Decision.PASS;
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), voted);
            acc = Decision.merge(acc, invokeDecision(e, "allowVoteOut",
                    () -> ((RoleMeetingHooks) e.callback()).allowVoteOut(voted, ctx)));
        }
        return acc;
    }

    /**
     * Folds {@code allowGameEnd} across every in-scope win entry.
     * {@link Decision#DENY} wins.
     */
    public Decision dispatchAllowGameEnd(@Nullable ServerLevel level, @Nullable String proposed,
                                         boolean loose) {
        Decision acc = Decision.PASS;
        for (ManagedHookEntry e : registry.allEntries(HookType.WIN_ALLOW_GAME_END)) {
            if (!inScope(e, level)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), level == null ? null : level.getServer());
            Decision next = invokeDecision(e, "allowGameEnd",
                    () -> ((RoleWinHooks) e.callback()).allowGameEnd(level, proposed, loose, ctx));
            acc = Decision.merge(acc, next);
        }
        return acc;
    }

    /**
     * Folds {@code evaluateWin} across every in-scope win entry.
     * Declare/replace overwrite; add/remove mutate.
     */
    public WinPatch dispatchEvaluateWin(@Nullable ServerLevel level, @Nullable String proposed,
                                        boolean loose) {
        WinPatch acc = WinPatch.noChange();
        for (ManagedHookEntry e : registry.allEntries(HookType.WIN_EVALUATE_WIN)) {
            if (!inScope(e, level)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), level == null ? null : level.getServer());
            WinPatch next = invokeResult(e, "evaluateWin",
                    () -> ((RoleWinHooks) e.callback()).evaluateWin(level, proposed, loose, ctx),
                    WinPatch.noChange());
            acc = WinPatch.merge(acc, next);
        }
        return acc;
    }

    /** Broadcasts a read-only settlement notification to every in-scope win hook. */
    public void dispatchAfterWinnersFinalized(@Nullable ServerLevel level, WinOutcome outcome) {
        WinOutcome locked = outcome == null ? WinOutcome.empty() : outcome;
        for (ManagedHookEntry e : registry.allEntries(HookType.WIN_AFTER_WINNERS_FINALIZED)) {
            if (!inScope(e, level)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), level == null ? null : level.getServer());
            invoke(e, "afterWinnersFinalized",
                    () -> ((RoleWinHooks) e.callback()).afterWinnersFinalized(level, locked, ctx));
        }
    }

    /**
     * The unified victory fold used by BOTH the standard SRE murder chain and the
     * blackout chain: gate ({@code allowGameEnd}) then winner patch
     * ({@code evaluateWin}). Callers read {@link WinFoldResult#denied()} and
     * {@link WinFoldResult#hasPatch()} according to their chain's semantics.
     */
    public WinFoldResult foldWin(@Nullable ServerLevel level, @Nullable String proposed, boolean loose) {
        Decision gate = dispatchAllowGameEnd(level, proposed, loose);
        WinPatch patch = dispatchEvaluateWin(level, proposed, loose);
        return new WinFoldResult(gate, patch);
    }

    /**
     * Blackout victory-checker entry. Returns {@code null} when no in-scope hook
     * wants to hijack. The {@code allowGameEnd} gate is reported separately by
     * {@link #foldWin}; {@code checkBlackoutWin} deliberately ignores it (a pride
     * DENY must not block a custom win declaration).
     */
    public @Nullable WinResult checkBlackoutWin(@Nullable ServerLevel level) {
        return foldWin(level, "BLACKOUT", false).toWinResult();
    }

    /** Dispatches an assignment notification. */
    public void dispatchOnAssigned(RoleKey role, @Nullable ServerPlayer player) {
        // ROLE_ASSIGNED is "start this assignment clean" — reset before the hook
        // so onAssigned observes defaults, matching RoleChange step 8 then 9.
        resetState(player, role, ResetCause.ROLE_ASSIGNED);
        List<ManagedHookEntry> entries = registry.entries(role, HookType.LIFECYCLE_ON_ASSIGNED);
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), player);
            invoke(e, "onAssigned",
                    () -> ((RoleLifecycleHooks) e.callback()).onAssigned(player, ctx));
        }
    }

    /** Dispatches a role-loss notification. */
    public void dispatchOnLost(RoleKey role, @Nullable ServerPlayer player) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.LIFECYCLE_ON_LOST);
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), player);
            invoke(e, "onLost", () -> ((RoleLifecycleHooks) e.callback()).onLost(player, ctx));
        }
    }

    /** Dispatches a game-start notification for one role. */
    public void dispatchOnGameStart(RoleKey role, @Nullable ServerLevel level) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.LIFECYCLE_ON_GAME_START);
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), level == null ? null : level.getServer());
            invoke(e, "onGameStart",
                    () -> ((RoleLifecycleHooks) e.callback()).onGameStart(level, ctx));
        }
    }

    /** Broadcasts game-start to every in-scope lifecycle entry. */
    public void dispatchOnGameStartAll(@Nullable ServerLevel level) {
        for (ManagedHookEntry e : registry.allEntries(HookType.LIFECYCLE_ON_GAME_START)) {
            if (!inScope(e, level)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), level == null ? null : level.getServer());
            invoke(e, "onGameStart",
                    () -> ((RoleLifecycleHooks) e.callback()).onGameStart(level, ctx));
        }
    }

    /** Dispatches a game-end notification for one role. */
    public void dispatchOnGameEnd(RoleKey role, @Nullable ServerLevel level) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.LIFECYCLE_ON_GAME_END);
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), level == null ? null : level.getServer());
            invoke(e, "onGameEnd",
                    () -> ((RoleLifecycleHooks) e.callback()).onGameEnd(level, ctx));
        }
    }

    /** Broadcasts game-end to every in-scope lifecycle entry. */
    public void dispatchOnGameEndAll(@Nullable ServerLevel level) {
        for (ManagedHookEntry e : registry.allEntries(HookType.LIFECYCLE_ON_GAME_END)) {
            if (!inScope(e, level)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), level == null ? null : level.getServer());
            invoke(e, "onGameEnd",
                    () -> ((RoleLifecycleHooks) e.callback()).onGameEnd(level, ctx));
        }
    }

    /** Dispatches a server-tick notification for one role. */
    public void dispatchServerTick(RoleKey role, MinecraftServer server) {
        List<ManagedHookEntry> entries = registry.entries(role, HookType.TICK_ON_SERVER_TICK);
        for (ManagedHookEntry e : entries) {
            if (!isEnabled(e)) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), server);
            invoke(e, "onServerTick",
                    () -> ((RoleTickHooks) e.callback()).onServerTick(server, ctx));
        }
    }

    /** Broadcasts a server-tick notification to every in-scope tick entry. */
    public void dispatchServerTickAll(MinecraftServer server) {
        ServerLevel level = anyLevel(server);
        long tick = server == null ? 0L : server.getTickCount();
        for (ManagedHookEntry e : registry.allEntries(HookType.TICK_ON_SERVER_TICK)) {
            if (!inScope(e, level)) {
                continue;
            }
            int interval = tickIntervalOf(e);
            if (interval > 1 && (tick % interval) != 0) {
                continue;
            }
            RoleHookContext ctx = ctx(e.role(), server);
            invoke(e, "onServerTick",
                    () -> ((RoleTickHooks) e.callback()).onServerTick(server, ctx));
        }
    }

    /** Reads a tick hook's scheduling tier, clamped to at least 1. */
    private static int tickIntervalOf(ManagedHookEntry e) {
        if (e.callback() instanceof RoleTickHooks hooks) {
            int interval = hooks.tickInterval();
            return interval < 1 ? 1 : interval;
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // Global listeners (runtime)
    // ------------------------------------------------------------------

    /** Registers the single global listener per event. Idempotent. */
    public synchronized void registerGlobalListeners() {
        if (listenersRegistered) {
            return;
        }
        listenersRegistered = true;

        AllowPlayerDeath.EVENT.register((player, deathReason) -> {
            RoleKey role = currentRole(player);
            if (role == null) {
                return true;
            }
            return dispatchAllowDeath(role, asServer(player), deathReason) != Decision.DENY;
        });

        AllowPlayerDeathWithKiller.EVENT.register((player, killer, deathReason) -> {
            RoleKey role = currentRole(player);
            if (role == null) {
                return true;
            }
            return dispatchAllowDeathByKiller(role, asServer(player), asServer(killer), deathReason)
                    != Decision.DENY;
        });

        OnPlayerDeath.EVENT.register((player, deathReason) -> {
            ServerPlayer dead = asServer(player);
            RoleKey role = currentRole(player);
            if (role != null) {
                dispatchOnDeath(role, dead, deathReason);
            }
            dispatchOnAnyDeath(dead, deathReason);
        });

        OnPlayerDeathWithKiller.EVENT.register((victim, killer, deathReason) -> {
            RoleKey killerRole = currentRole(killer);
            if (killerRole != null) {
                dispatchOnKill(killerRole, asServer(victim), asServer(killer), deathReason);
            }
        });

        OnDeathWithBody.EVENT.register((victim, killer, deathReason, body) -> {
            RoleKey victimRole = currentRole(victim);
            RoleKey killerRole = currentRole(killer);
            ServerPlayer dead = asServer(victim);
            ServerPlayer killerSp = asServer(killer);
            if (victimRole != null) {
                dispatchOnDeathWithBody(victimRole, dead, killerSp, deathReason, body);
            }
            if (killerRole != null && !killerRole.equals(victimRole)) {
                dispatchOnDeathWithBody(killerRole, dead, killerSp, deathReason, body);
            }
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (world.isClientSide) {
                return InteractionResultHolder.pass(stack);
            }
            RoleKey role = currentRole(player);
            if (role == null) {
                return InteractionResultHolder.pass(stack);
            }
            InteractionResult result = dispatchUseItem(role, asServer(player), stack, hand);
            if (result == InteractionResult.PASS) {
                return InteractionResultHolder.pass(stack);
            }
            return new InteractionResultHolder<>(result, player.getItemInHand(hand));
        });

        OnGameStarted.EVENT.register(level -> {
            resetCircuits();
            com.habitrain.core.role.snapshot.RoleSnapshotManager.INSTANCE.beginRound();
            resetState(null, null, ResetCause.ROUND_START);
            dispatchOnGameStartAll(level);
        });

        OnGameEnd.EVENT.register((level, gameWorldComponent) -> {
            dispatchOnGameEndAll(level);
            // Settlement is read-only and must see state before ROUND_END reset.
            dispatchAfterWinnersFinalized(level, readWinOutcome(level));
            resetState(null, null, ResetCause.ROUND_END);
            com.habitrain.core.role.snapshot.RoleSnapshotManager.INSTANCE.endRound();
            com.habitrain.core.role.snapshot.RoleSnapshotManager.INSTANCE.activatePending();
        });

        // Registered after SinVictoryHooks (which prepends itself) so pride/sloth/lust
        // still win the first-non-NOT_MODIFY race. DENY maps to NONE (do not end).
        AllowGameEnd.EVENT.register((level, proposed, loose) -> {
            String name = proposed == null ? null : proposed.name();
            WinFoldResult fold = foldWin(level, name, loose);
            if (fold.denied()) {
                return GameUtils.WinStatus.NONE;
            }
            return applyWinPatch(level, fold.patch());
        });

        MeetingStartEvent.EVENT.register((level, reporter) -> dispatchOnMeetingStart(level, reporter));
        MeetingEndEvent.EVENT.register(this::dispatchOnMeetingEnd);
        MeetingVoteOutEvent.EVENT.register((level, player) -> {
            RoleKey role = currentRole(player);
            if (role == null) {
                return true;
            }
            return dispatchAllowVoteOut(role, asServer(player)) != Decision.DENY;
        });

        ModdedRoleAssigned.EVENT.register((player, role) -> {
            if (player instanceof ServerPlayer sp && role != null && role.identifier() != null) {
                dispatchOnAssigned(RoleKey.of(role.identifier()), sp);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(this::dispatchServerTickAll);

        LOGGER.info("RoleEventDispatcher registered global listeners");
    }

    private static @Nullable ServerPlayer asServer(Player player) {
        return player instanceof ServerPlayer sp ? sp : null;
    }

    private static @Nullable RoleKey currentRole(Player player) {
        if (player == null || player.level() == null) {
            return null;
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.level());
            if (game == null) {
                return null;
            }
            SRERole role = game.getRole(player);
            if (role == null || role.identifier() == null) {
                return null;
            }
            return RoleKey.of(role.identifier());
        } catch (Throwable t) {
            return null;
        }
    }

    private static @Nullable ServerLevel levelOf(@Nullable Player player) {
        return player != null && player.level() instanceof ServerLevel sl ? sl : null;
    }

    private static @Nullable ServerLevel anyLevel(@Nullable MinecraftServer server) {
        if (server == null) {
            return null;
        }
        try {
            return server.overworld();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean inScope(ManagedHookEntry e, @Nullable ServerLevel level) {
        return isEnabled(e) && RoleScopeEvaluator.evaluate(e.scope(), e.role(), level, hookGates,
                RoleSnapshotManager.INSTANCE.current());
    }

    /**
     * Provider and entry gates come from the frozen snapshot whenever one is
     * published.  The live config is only the pre-snapshot bootstrap fallback,
     * so a NEXT_ROUND edit cannot alter an active round's hook behavior.
     */
    private static boolean isEnabled(ManagedHookEntry entry) {
        var snapshot = RoleSnapshotManager.INSTANCE.current();
        if (snapshot != null) {
            return snapshot.isBehaviorEntryEnabled(entry.providerId(), entry.entryId());
        }
        return com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE
                .gateFor(entry.providerId(), entry.entryId())
                == com.habitrain.core.role.config.RoleExtensionConfigService.EntryGate.ENABLED;
    }

    private RoleHookContext ctx(RoleKey role, @Nullable ServerPlayer player) {
        return ctx(role, player == null ? null : player.getServer());
    }

    private RoleHookContext ctx(RoleKey role, @Nullable MinecraftServer server) {
        return new RoleHookContext(role, snapshotProvider.get(), server);
    }

    /**
     * Translates a folded {@link WinPatch} into an upstream {@link GameUtils.WinStatus}.
     * Writes custom winners onto the round-end component when the patch is custom.
     */
    private static GameUtils.WinStatus applyWinPatch(@Nullable ServerLevel level, @Nullable WinPatch patch) {
        if (patch == null || patch.op() == WinPatchOp.NO_CHANGE) {
            return GameUtils.WinStatus.NOT_MODIFY;
        }
        return switch (patch.op()) {
            case DECLARE_CUSTOM, REPLACE_WINNERS, ADD_WINNER, REMOVE_WINNER -> {
                applyCustomWinners(level, patch);
                yield GameUtils.WinStatus.CUSTOM;
            }
            case DECLARE_FACTION_WIN -> factionStatus(patch.faction());
            case NO_CHANGE -> GameUtils.WinStatus.NOT_MODIFY;
        };
    }

    private static GameUtils.WinStatus factionStatus(@Nullable String faction) {
        if (faction == null || faction.isBlank()) {
            return GameUtils.WinStatus.CUSTOM;
        }
        String key = faction.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "KILLER", "KILLERS", "BAD" -> GameUtils.WinStatus.KILLERS;
            case "INNOCENT", "INNOCENTS", "PASSENGER", "PASSENGERS", "GOOD" -> GameUtils.WinStatus.PASSENGERS;
            case "TIME" -> GameUtils.WinStatus.TIME;
            case "LOVERS" -> GameUtils.WinStatus.LOVERS;
            default -> {
                try {
                    yield GameUtils.WinStatus.valueOf(key);
                } catch (IllegalArgumentException ignored) {
                    yield GameUtils.WinStatus.CUSTOM;
                }
            }
        };
    }

    private static void applyCustomWinners(@Nullable ServerLevel level, WinPatch patch) {
        if (level == null || patch == null) {
            return;
        }
        try {
            SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
            if (roundEnd == null) {
                return;
            }
            if (roundEnd.CustomWinnerPlayers == null) {
                roundEnd.CustomWinnerPlayers = new ArrayList<>();
            } else {
                roundEnd.CustomWinnerPlayers.clear();
            }
            roundEnd.CustomWinnerPlayers.addAll(patch.winners());
            if (patch.customId() != null && !patch.customId().isBlank()) {
                roundEnd.CustomWinnerID = patch.customId();
            }
            if (patch.reason() != null && !patch.reason().isBlank()) {
                roundEnd.CustomWinnerSubtitle = Component.literal(patch.reason());
            }
        } catch (Throwable t) {
            LOGGER.warn("failed to apply custom winners from WinPatch", t);
        }
    }

    private static WinOutcome readWinOutcome(@Nullable ServerLevel level) {
        if (level == null) {
            return WinOutcome.empty();
        }
        try {
            SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
            if (roundEnd == null) {
                return WinOutcome.empty();
            }
            String status = roundEnd.getWinStatus() == null ? null : roundEnd.getWinStatus().name();
            List<UUID> winners = new ArrayList<>();
            if (roundEnd.CustomWinnerPlayers != null) {
                winners.addAll(roundEnd.CustomWinnerPlayers);
            }
            String reason = roundEnd.CustomWinnerID;
            if ((reason == null || reason.isBlank()) && roundEnd.CustomWinnerSubtitle != null) {
                reason = roundEnd.CustomWinnerSubtitle.getString();
            }
            return new WinOutcome(status, winners, reason);
        } catch (Throwable t) {
            LOGGER.debug("failed to read win outcome", t);
            return WinOutcome.empty();
        }
    }

    private static void resetState(@Nullable ServerPlayer player, @Nullable RoleKey role, ResetCause cause) {
        try {
            RoleStateApi.instance().reset(player, role, cause);
        } catch (Throwable t) {
            LOGGER.warn("role-state reset {} for {} failed", cause, role, t);
        }
    }

    // ------------------------------------------------------------------
    // Isolation + circuit breaker
    // ------------------------------------------------------------------

    private void invoke(ManagedHookEntry e, String hookName, Runnable action) {
        refreshCircuits();
        HookCircuit circuit = circuit(e);
        if (circuit.isBroken()) {
            return;
        }
        long start = System.nanoTime();
        try {
            action.run();
            circuit.recordSuccess(System.nanoTime() - start);
        } catch (Throwable t) {
            circuit.recordFailure(System.nanoTime() - start);
            LOGGER.error("Hook {} for {} failed (provider={}, entry={})",
                    hookName, e.role(), e.providerId(), e.entryId(), t);
        }
    }

    private <T> T invokeResult(ManagedHookEntry e, String hookName, Supplier<T> action, T fallback) {
        refreshCircuits();
        HookCircuit circuit = circuit(e);
        if (circuit.isBroken()) {
            return fallback;
        }
        long start = System.nanoTime();
        try {
            T value = action.get();
            circuit.recordSuccess(System.nanoTime() - start);
            return value == null ? fallback : value;
        } catch (Throwable t) {
            circuit.recordFailure(System.nanoTime() - start);
            LOGGER.error("Hook {} for {} failed (provider={}, entry={})",
                    hookName, e.role(), e.providerId(), e.entryId(), t);
            return fallback;
        }
    }

    private Decision invokeDecision(ManagedHookEntry e, String hookName, Supplier<Decision> action) {
        refreshCircuits();
        HookCircuit circuit = circuit(e);
        if (circuit.isBroken()) {
            return Decision.PASS;
        }
        long start = System.nanoTime();
        try {
            Decision d = action.get();
            circuit.recordSuccess(System.nanoTime() - start);
            return d;
        } catch (Throwable t) {
            circuit.recordFailure(System.nanoTime() - start);
            LOGGER.error("Hook {} for {} failed (provider={}, entry={})",
                    hookName, e.role(), e.providerId(), e.entryId(), t);
            return Decision.PASS;
        }
    }

    /**
     * A new snapshot clears every circuit (fix-doc §9.4): trips are per-round, not
     * process-wide, and a fresh round starts from clean breaker state.
     */
    private void refreshCircuits() {
        try {
            RoleSnapshotId current = snapshotProvider.get();
            if (current != null && !current.equals(lastSeenSnapshot)) {
                lastSeenSnapshot = current;
                circuits.clear();
            }
        } catch (Throwable ignored) {
            // A failing snapshot supplier must not break dispatch.
        }
    }

    /** Snapshot of every observed hook for {@code /habitrain roleapi perf}. */
    public List<com.habitrain.core.api.role.v2.HookPerfEntry> perf() {
        List<com.habitrain.core.api.role.v2.HookPerfEntry> out = new ArrayList<>();
        for (Map.Entry<CircuitKey, HookCircuit> entry : circuits.entrySet()) {
            CircuitKey k = entry.getKey();
            HookCircuit c = entry.getValue();
            out.add(new com.habitrain.core.api.role.v2.HookPerfEntry(
                    k.role(), k.type().name().toLowerCase(Locale.ROOT),
                    c.consecutiveFailures, c.broken, c.invocations, c.lastNanos, c.budgetNanos,
                    k.providerId(), k.entryId()));
        }
        return List.copyOf(out);
    }

    public List<String> describePerf() {
        List<String> lines = new ArrayList<>();
        List<com.habitrain.core.api.role.v2.HookPerfEntry> rows = perf();
        long broken = rows.stream().filter(com.habitrain.core.api.role.v2.HookPerfEntry::broken).count();
        lines.add("perf hooks=" + rows.size() + " broken=" + broken
                + " budgetNs=" + DEFAULT_BUDGET_NANOS);
        if (rows.isEmpty()) {
            lines.add("  (none)");
            return lines;
        }
        for (com.habitrain.core.api.role.v2.HookPerfEntry row : rows) {
            lines.add("  " + row.role() + " " + row.hook()
                    + " provider=" + row.providerId() + " entry=" + row.entryId()
                    + " calls=" + row.invocations()
                    + " lastNs=" + row.lastNanos()
                    + " fail=" + row.failures()
                    + (row.broken() ? " BROKEN" : "")
                    + (row.lastNanos() > row.budgetNanos() ? " OVER_BUDGET" : ""));
        }
        return lines;
    }

    /** Clears every circuit. Called at round start so a trip is per-round, not process-wide. */
    public void resetCircuits() {
        circuits.clear();
    }

    private HookCircuit circuit(ManagedHookEntry e) {
        return circuits.computeIfAbsent(
                new CircuitKey(e.role(), e.type(), e.providerId(), e.entryId()),
                k -> new HookCircuit());
    }

    private record CircuitKey(RoleKey role, HookType type, String providerId, String entryId) {}

    private static final class HookCircuit {
        private int consecutiveFailures;
        private boolean broken;
        private long invocations;
        private long lastNanos;
        private long budgetNanos = DEFAULT_BUDGET_NANOS;

        boolean isBroken() {
            return broken;
        }

        void recordSuccess(long nanos) {
            consecutiveFailures = 0;
            recordTiming(nanos);
        }

        void recordFailure(long nanos) {
            recordTiming(nanos);
            if (++consecutiveFailures >= CIRCUIT_THRESHOLD) {
                broken = true;
            }
        }

        private void recordTiming(long nanos) {
            invocations++;
            lastNanos = nanos;
        }
    }
}
