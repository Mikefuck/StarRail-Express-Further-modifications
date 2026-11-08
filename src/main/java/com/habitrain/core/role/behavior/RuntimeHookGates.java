package com.habitrain.core.role.behavior;

import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Snapshot-backed {@link HookGates} bound by core at runtime. {@link
 * #presentInRound} reads the frozen round snapshot (never pending); {@link
 * #activeHolder} answers from a per-server-tick cache of online players' current
 * roles, so broadcasting over N role entries does not rescan M players per role
 * (fix-doc §11.4 cached query). A failed lookup is fail-closed: executing a
 * provider callback outside its known scope is more dangerous than omitting one
 * callback for the current event.
 */
public final class RuntimeHookGates implements HookGates {

    public static final RuntimeHookGates INSTANCE = new RuntimeHookGates();

    private volatile Set<RoleKey> activeRoles = Set.of();
    private volatile java.util.Map<RoleKey, ServerPlayer> activeHolders = java.util.Map.of();
    private volatile java.util.Map<RoleKey, java.util.List<ServerPlayer>> activeHoldersAll = java.util.Map.of();
    private volatile int activeRolesTick = Integer.MIN_VALUE;

    private RuntimeHookGates() {}

    /** Invalidates the per-tick active-holder cache on mid-tick role changes. */
    public void invalidate() {
        this.activeRolesTick = Integer.MIN_VALUE;
    }

    @Override
    public boolean activeHolder(RoleKey role, @Nullable ServerLevel level) {
        if (level == null || level.getServer() == null || role == null) {
            return false;
        }
        refreshActiveRoles(level);
        RoleKey canonical = canonicalize(role);
        return activeRoles.contains(role) || activeRoles.contains(canonical);
    }

    /** Rebuilds the active-role set once per server tick (server thread only). */
    private void refreshActiveRoles(@Nullable ServerLevel level) {
        int tick = level.getServer().getTickCount();
        if (tick == activeRolesTick) {
            return;
        }
        Set<RoleKey> roles = new HashSet<>();
        java.util.Map<RoleKey, ServerPlayer> holders = new java.util.LinkedHashMap<>();
        java.util.Map<RoleKey, java.util.List<ServerPlayer>> holdersAll = new java.util.LinkedHashMap<>();
        try {
            for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                try {
                    SREGameWorldComponent game = SREGameWorldComponent.KEY.get(p.level());
                    if (game == null) {
                        continue;
                    }
                    SRERole r = game.getRole(p);
                    if (r != null && r.identifier() != null) {
                        RoleKey key = RoleKey.of(r.identifier());
                        RoleKey canonical = canonicalize(key);
                        roles.add(key);
                        roles.add(canonical);
                        holders.putIfAbsent(key, p);
                        holders.putIfAbsent(canonical, p);
                        holdersAll.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(p);
                        if (!canonical.equals(key)) {
                            holdersAll.computeIfAbsent(canonical, k -> new java.util.ArrayList<>()).add(p);
                        }
                    }
                } catch (Throwable ignored) {
                    // one player's lookup failure must not hide other holders.
                }
            }
        } catch (Throwable t) {
            // Fail closed: leave the role set empty for this tick.
        }
        this.activeRoles = roles;
        this.activeHolders = holders;
        this.activeHoldersAll = holdersAll;
        this.activeRolesTick = tick;
    }

    @Override
    public boolean presentInRound(RoleKey role, @Nullable ServerLevel level) {
        try {
            Optional<RoleSnapshot> snap = RoleCatalogApi.instance().currentSnapshot();
            // Fail-closed（与类 javadoc 一致，review L10）：无快照时放行广播钩子
            // 比漏发一次事件更危险，orElse(true) 会把缺失快照当作"全部在场"。
            RoleKey canonical = canonicalize(role);
            return snap.map(s -> s.isActive(role.location()) || s.isActive(canonical.location())).orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public @Nullable ServerPlayer holder(RoleKey role, @Nullable ServerLevel level) {
        if (level == null || level.getServer() == null || role == null) return null;
        refreshActiveRoles(level);
        ServerPlayer direct = activeHolders.get(role);
        return direct != null ? direct : activeHolders.get(canonicalize(role));
    }

    public java.util.List<ServerPlayer> holders(RoleKey role, @Nullable ServerLevel level) {
        if (level == null || level.getServer() == null || role == null) return java.util.List.of();
        refreshActiveRoles(level);
        var list = activeHoldersAll.get(role);
        if (list == null || list.isEmpty()) {
            list = activeHoldersAll.get(canonicalize(role));
        }
        return list == null ? java.util.List.of() : java.util.List.copyOf(list);
    }

    private static RoleKey canonicalize(RoleKey role) {
        if (role == null) return null;
        try {
            return RoleCatalogApi.instance().canonicalize(role.location());
        } catch (Throwable ignored) {
            return role;
        }
    }
}
