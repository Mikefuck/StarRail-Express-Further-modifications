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
    private volatile int activeRolesTick = Integer.MIN_VALUE;

    private RuntimeHookGates() {}

    @Override
    public boolean activeHolder(RoleKey role, @Nullable ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return false;
        }
        refreshActiveRoles(level);
        return activeRoles.contains(role);
    }

    /** Rebuilds the active-role set once per server tick (server thread only). */
    private void refreshActiveRoles(@Nullable ServerLevel level) {
        int tick = level.getServer().getTickCount();
        if (tick == activeRolesTick) {
            return;
        }
        Set<RoleKey> roles = new HashSet<>();
        try {
            for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                try {
                    SREGameWorldComponent game = SREGameWorldComponent.KEY.get(p.level());
                    if (game == null) {
                        continue;
                    }
                    SRERole r = game.getRole(p);
                    if (r != null && r.identifier() != null) {
                        roles.add(RoleKey.of(r.identifier()));
                    }
                } catch (Throwable ignored) {
                    // one player's lookup failure must not hide other holders.
                }
            }
        } catch (Throwable t) {
            // Fail closed: leave the role set empty for this tick.
        }
        this.activeRoles = roles;
        this.activeRolesTick = tick;
    }

    @Override
    public boolean presentInRound(RoleKey role, @Nullable ServerLevel level) {
        try {
            Optional<RoleSnapshot> snap = RoleCatalogApi.instance().currentSnapshot();
            // Fail-closed（与类 javadoc 一致，review L10）：无快照时放行广播钩子
            // 比漏发一次事件更危险，orElse(true) 会把缺失快照当作"全部在场"。
            return snap.map(s -> s.isActive(role.location())).orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }
}
