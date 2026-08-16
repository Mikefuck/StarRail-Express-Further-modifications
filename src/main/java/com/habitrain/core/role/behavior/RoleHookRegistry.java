package com.habitrain.core.role.behavior;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.behavior.RoleCombatHooks;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleInteractionHooks;
import com.habitrain.core.api.role.v2.behavior.RoleLifecycleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleMeetingHooks;
import com.habitrain.core.api.role.v2.behavior.RoleScope;
import com.habitrain.core.api.role.v2.behavior.RoleShopHooks;
import com.habitrain.core.api.role.v2.behavior.RoleTaskHooks;
import com.habitrain.core.api.role.v2.behavior.RoleTickHooks;
import com.habitrain.core.api.role.v2.behavior.RoleWinHooks;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects the managed behavior hooks providers attach to roles via the v2
 * {@code hooks(...)} registration.
 *
 * <p>One {@link RoleHooks} container is decomposed into per-{@link HookType}
 * {@link ManagedHookEntry}s, ordered per {@code (role, hookType)} by
 * {@link ManagedHookEntry#ORDER priority → provider → entryKey}. Providers are
 * never merged away: two providers hooking the same category for the same role
 * both execute in stable order (fix-doc §9.1).
 */
public final class RoleHookRegistry {

    public static final RoleHookRegistry INSTANCE = new RoleHookRegistry();
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleHookRegistry");
    /** Provider id used by the process-wide registration path (core's own roles). */
    public static final String DEFAULT_PROVIDER = "habitrain_core";

    private final Map<RoleKey, Map<HookType, List<ManagedHookEntry>>> hooks = new LinkedHashMap<>();
    private boolean frozen;

    private RoleHookRegistry() {}

    public static void init() {
        LOGGER.info("RoleHookRegistry initialized");
    }

    /**
     * Provider-scoped registration. Decomposes the container into per-type entries
     * and inserts each into the role's stable list. A duplicate of the same
     * {@code (providerId, entryId, role, type)} is skipped so a re-registration is
     * idempotent rather than accumulating.
     */
    public synchronized void register(RoleKey role, RoleScope scope, String providerId,
                                      String entryId, PatchPriority priority, RoleHooks hooks) {
        if (frozen) {
            throw new IllegalStateException("Role hook registry is frozen");
        }
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(hooks, "hooks");
        if (hooks.isEmpty()) {
            return;
        }
        Map<HookType, List<ManagedHookEntry>> byType =
                this.hooks.computeIfAbsent(role, k -> new LinkedHashMap<>());
        for (DecomposedHook d : decompose(hooks)) {
            for (HookType type : d.types) {
                List<ManagedHookEntry> list = byType.computeIfAbsent(type, k -> new ArrayList<>());
                ManagedHookEntry entry = new ManagedHookEntry(
                        providerId, entryId, role, scope, priority, type, d.callback);
                insertSorted(list, entry);
            }
        }
        LOGGER.info("Registered hooks for {} (provider={}, entry={})", role, providerId, entryId);
    }

    /** Convenience registration (process-wide path / tests): HOLDER scope, NORMAL priority. */
    public void register(RoleKey role, RoleHooks hooks) {
        register(role, RoleScope.HOLDER, DEFAULT_PROVIDER, "hooks@" + role, PatchPriority.NORMAL, hooks);
    }

    /** The stable, ordered entries for one role + hook type. Empty when none. */
    public List<ManagedHookEntry> entries(RoleKey role, HookType type) {
        Map<HookType, List<ManagedHookEntry>> byType = hooks.get(role);
        if (byType == null) {
            return List.of();
        }
        List<ManagedHookEntry> list = byType.get(type);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Every registered entry for a hook type, in role-insertion then stable order. */
    public List<ManagedHookEntry> allEntries(HookType type) {
        List<ManagedHookEntry> out = new ArrayList<>();
        for (Map<HookType, List<ManagedHookEntry>> byType : hooks.values()) {
            List<ManagedHookEntry> list = byType.get(type);
            if (list != null) {
                out.addAll(list);
            }
        }
        return out;
    }

    /** Whether any hooks are registered for the role. */
    public boolean hasHooks(RoleKey role) {
        return hooks.containsKey(role);
    }

    /**
     * Reconstructs a merged {@link RoleHooks} container for diagnostics. The first
     * entry (stable order) wins per category; used by the {@code hooks} command.
     */
    public @Nullable RoleHooks get(RoleKey role) {
        Map<HookType, List<ManagedHookEntry>> byType = hooks.get(role);
        if (byType == null || byType.isEmpty()) {
            return null;
        }
        RoleHooks.Builder b = RoleHooks.builder();
        RoleLifecycleHooks lifecycle = firstCallback(byType, HookType.Category.LIFECYCLE);
        if (lifecycle != null) b.lifecycle(lifecycle);
        RoleCombatHooks combat = firstCallback(byType, HookType.Category.COMBAT);
        if (combat != null) b.combat(combat);
        RoleTickHooks tick = firstCallback(byType, HookType.Category.TICK);
        if (tick != null) b.tick(tick);
        RoleInteractionHooks interaction = firstCallback(byType, HookType.Category.INTERACTION);
        if (interaction != null) b.interaction(interaction);
        RoleShopHooks shop = firstCallback(byType, HookType.Category.SHOP);
        if (shop != null) b.shop(shop);
        RoleTaskHooks task = firstCallback(byType, HookType.Category.TASK);
        if (task != null) b.task(task);
        RoleMeetingHooks meeting = firstCallback(byType, HookType.Category.MEETING);
        if (meeting != null) b.meeting(meeting);
        RoleWinHooks win = firstCallback(byType, HookType.Category.WIN);
        if (win != null) b.win(win);
        return b.build();
    }

    /** Unmodifiable per-role merged view, for diagnostics. */
    public Map<RoleKey, RoleHooks> getAll() {
        Map<RoleKey, RoleHooks> out = new LinkedHashMap<>();
        for (RoleKey role : hooks.keySet()) {
            RoleHooks merged = get(role);
            if (merged != null) {
                out.put(role, merged);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** The raw per-role entry tables (read-only), for perf/attribute diagnostics. */
    public Map<RoleKey, Map<HookType, List<ManagedHookEntry>>> entryView() {
        return Collections.unmodifiableMap(hooks);
    }

    /** Prevents further registrations (called on server start). */
    public synchronized void freeze() {
        this.frozen = true;
    }

    /** A transaction-local copy of every hook table and its frozen state. */
    public synchronized RegistrationSnapshot snapshotForTransaction() {
        Map<RoleKey, Map<HookType, List<ManagedHookEntry>>> copy = new LinkedHashMap<>();
        for (var role : hooks.entrySet()) {
            Map<HookType, List<ManagedHookEntry>> byType = new LinkedHashMap<>();
            for (var type : role.getValue().entrySet()) {
                byType.put(type.getKey(), new ArrayList<>(type.getValue()));
            }
            copy.put(role.getKey(), byType);
        }
        return new RegistrationSnapshot(copy, frozen);
    }

    /** Restores the precise table captured before a provider commit. */
    public synchronized void restoreTransactionSnapshot(RegistrationSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        hooks.clear();
        for (var role : snapshot.hooks().entrySet()) {
            Map<HookType, List<ManagedHookEntry>> byType = new LinkedHashMap<>();
            for (var type : role.getValue().entrySet()) {
                byType.put(type.getKey(), new ArrayList<>(type.getValue()));
            }
            hooks.put(role.getKey(), byType);
        }
        frozen = snapshot.frozen();
    }

    /** Test isolation: drops all entries and unfreezes. */
    public synchronized void clear() {
        hooks.clear();
        frozen = false;
    }

    private static void insertSorted(List<ManagedHookEntry> list, ManagedHookEntry entry) {
        for (int i = 0; i < list.size(); i++) {
            ManagedHookEntry existing = list.get(i);
            if (existing.providerId().equals(entry.providerId())
                    && existing.entryId().equals(entry.entryId())
                    && existing.role().equals(entry.role())
                    && existing.type() == entry.type()) {
                return; // idempotent re-registration
            }
            if (ManagedHookEntry.ORDER.compare(existing, entry) > 0) {
                list.add(i, entry);
                return;
            }
        }
        list.add(entry);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable <T> T firstCallback(Map<HookType, List<ManagedHookEntry>> byType,
                                                 HookType.Category category) {
        for (HookType type : HookType.values()) {
            if (type.category() != category) {
                continue;
            }
            List<ManagedHookEntry> list = byType.get(type);
            if (list != null && !list.isEmpty()) {
                return (T) list.getFirst().callback();
            }
        }
        return null;
    }

    private record DecomposedHook(Object callback, List<HookType> types) {}

    public record RegistrationSnapshot(
            Map<RoleKey, Map<HookType, List<ManagedHookEntry>>> hooks,
            boolean frozen) {}

    private static List<DecomposedHook> decompose(RoleHooks hooks) {
        List<DecomposedHook> out = new ArrayList<>();
        if (hooks.lifecycle() != null) {
            out.add(new DecomposedHook(hooks.lifecycle(), types(
                    HookType.LIFECYCLE_ON_ASSIGNED, HookType.LIFECYCLE_ON_LOST,
                    HookType.LIFECYCLE_ON_GAME_START, HookType.LIFECYCLE_ON_GAME_TRUE_START,
                    HookType.LIFECYCLE_ON_GAME_END, HookType.LIFECYCLE_ON_ROLES_CONFIRM)));
        }
        if (hooks.combat() != null) {
            out.add(new DecomposedHook(hooks.combat(), types(
                    HookType.COMBAT_ALLOW_DEATH, HookType.COMBAT_ON_DEATH, HookType.COMBAT_ON_KILL,
                    HookType.COMBAT_ALLOW_DEATH_BY_KILLER, HookType.COMBAT_ALLOW_KILL_BY_KILLER,
                    HookType.COMBAT_ON_ANY_DEATH, HookType.COMBAT_ON_DEATH_WITH_BODY)));
        }
        if (hooks.tick() != null) {
            out.add(new DecomposedHook(hooks.tick(), types(HookType.TICK_ON_SERVER_TICK)));
        }
        if (hooks.interaction() != null) {
            out.add(new DecomposedHook(hooks.interaction(), types(
                    HookType.INTERACTION_USE_ITEM, HookType.INTERACTION_USE_ENTITY,
                    HookType.INTERACTION_USE_BLOCK, HookType.INTERACTION_ATTACK_ENTITY,
                    HookType.INTERACTION_ATTACK_BLOCK, HookType.INTERACTION_BLOCK_BREAK)));
        }
        if (hooks.shop() != null) {
            out.add(new DecomposedHook(hooks.shop(), types(
                    HookType.SHOP_ALLOW_BUY, HookType.SHOP_ON_BUY, HookType.SHOP_ON_ANY_BUY)));
        }
        if (hooks.task() != null) {
            out.add(new DecomposedHook(hooks.task(), types(HookType.TASK_ON_FINISH_QUEST)));
        }
        if (hooks.meeting() != null) {
            out.add(new DecomposedHook(hooks.meeting(), types(
                    HookType.MEETING_ON_START, HookType.MEETING_ON_END, HookType.MEETING_ALLOW_VOTE_OUT)));
        }
        if (hooks.win() != null) {
            out.add(new DecomposedHook(hooks.win(), types(
                    HookType.WIN_ALLOW_GAME_END, HookType.WIN_EVALUATE_WIN, HookType.WIN_AFTER_WINNERS_FINALIZED)));
        }
        return out;
    }

    private static List<HookType> types(HookType... types) {
        return List.of(types);
    }
}
