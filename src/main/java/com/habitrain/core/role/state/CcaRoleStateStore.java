package com.habitrain.core.role.state;

import com.habitrain.core.api.role.v2.state.StateScope;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Production {@link RoleStateStore} that routes each slot to the fixed CCA
 * container owning its scope (fix-doc §10.2): PLAYER → the player's
 * {@link HabiRolePlayerStateComponent}, WORLD → the world's
 * {@link HabiRoleWorldStateComponent}, ROUND → {@link HabiRoleRoundStateStore}.
 *
 * <p>Entity/world resolution defaults to the server bound via
 * {@link RuntimeRoleServer}; a custom resolver can be injected for tests.
 * This class is production-only; unit tests exercise the behavior through
 * {@link MemoryRoleStateStore} instead.
 */
public final class CcaRoleStateStore implements RoleStateStore {

    /** Resolves live entities for slot routing. */
    public interface EntityResolver {
        @Nullable ServerPlayer player(java.util.UUID id);

        @Nullable ServerLevel world(String worldKey);

        Collection<ServerPlayer> allPlayers();

        Collection<ServerLevel> allWorlds();
    }

    private static final EntityResolver BOUND_SERVER = new EntityResolver() {
        @Override
        public @Nullable ServerPlayer player(java.util.UUID id) {
            MinecraftServer server = RuntimeRoleServer.INSTANCE.server();
            return server == null ? null : server.getPlayerList().getPlayer(id);
        }

        @Override
        public @Nullable ServerLevel world(String worldKey) {
            MinecraftServer server = RuntimeRoleServer.INSTANCE.server();
            if (server == null || worldKey == null) {
                return null;
            }
            ResourceLocation loc = ResourceLocation.tryParse(worldKey);
            return loc == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
        }

        @Override
        public Collection<ServerPlayer> allPlayers() {
            MinecraftServer server = RuntimeRoleServer.INSTANCE.server();
            return server == null ? List.of() : server.getPlayerList().getPlayers();
        }

        @Override
        public Collection<ServerLevel> allWorlds() {
            MinecraftServer server = RuntimeRoleServer.INSTANCE.server();
            if (server == null) {
                return List.of();
            }
            java.util.List<ServerLevel> worlds = new java.util.ArrayList<>();
            for (ServerLevel w : server.getAllLevels()) {
                worlds.add(w);
            }
            return worlds;
        }
    };

    private volatile EntityResolver resolver = BOUND_SERVER;

    public CcaRoleStateStore() {
    }

    public void setResolver(EntityResolver resolver) {
        this.resolver = resolver == null ? BOUND_SERVER : resolver;
    }

    @Override
    public @Nullable StoredState read(StateSlotKey key) {
        StateSlotBag bag = bagFor(key);
        return bag == null ? null : bag.read(key.encode());
    }

    @Override
    public void write(StateSlotKey key, StoredState state) {
        StateSlotBag bag = bagFor(key);
        if (bag != null) {
            bag.write(key.encode(), state);
        }
    }

    @Override
    public void remove(StateSlotKey key) {
        StateSlotBag bag = bagFor(key);
        if (bag != null) {
            bag.remove(key.encode());
        }
    }

    @Override
    public void removeWhere(Predicate<StateSlotKey> filter) {
        if (filter == null) {
            return;
        }
        Predicate<String> encoded = slot -> {
            StateSlotKey key = StateSlotKey.parse(slot);
            return key != null && filter.test(key);
        };
        for (ServerPlayer p : resolver.allPlayers()) {
            HabiRolePlayerStateComponent.KEY.get(p).slots().keySet().removeIf(encoded);
        }
        for (ServerLevel w : resolver.allWorlds()) {
            HabiRoleWorldStateComponent.KEY.get(w).slots().keySet().removeIf(encoded);
        }
        HabiRoleRoundStateStore.INSTANCE.slots().keySet().removeIf(encoded);
    }

    @Override
    public void clearAll() {
        for (ServerPlayer p : resolver.allPlayers()) {
            HabiRolePlayerStateComponent.KEY.get(p).clear();
        }
        for (ServerLevel w : resolver.allWorlds()) {
            HabiRoleWorldStateComponent.KEY.get(w).clear();
        }
        HabiRoleRoundStateStore.INSTANCE.clear();
    }

    @Override
    public Map<StateSlotKey, StoredState> exportAll() {
        throw new UnsupportedOperationException(
                "CcaRoleStateStore persists through world NBT; exportAll is memory-store only");
    }

    @Override
    public void importAll(Map<StateSlotKey, StoredState> data) {
        throw new UnsupportedOperationException(
                "CcaRoleStateStore persists through world NBT; importAll is memory-store only");
    }

    private @Nullable StateSlotBag bagFor(StateSlotKey key) {
        if (key.scope() == StateScope.PLAYER) {
            ServerPlayer p = resolver.player(key.playerId());
            return p == null ? null : HabiRolePlayerStateComponent.KEY.get(p);
        }
        if (key.scope() == StateScope.WORLD) {
            if (key.worldKey() == null) {
                return null;
            }
            ServerLevel w = resolver.world(key.worldKey());
            return w == null ? null : HabiRoleWorldStateComponent.KEY.get(w);
        }
        return HabiRoleRoundStateStore.INSTANCE;
    }
}
