package com.habitrain.core.game.blackout;

import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停电模式 — 阵营/职业管理器。
 * 预留扩展接口：后续添加新角色只需新增 enum 值 + 修改分配逻辑。
 *
 * 支持多维度同时游戏：状态按维度(ServerLevel)隔离。
 */
public class BlackoutRoleManager {

    public enum Faction {
        GOOD,
        BAD
    }

    public enum RoleType {
        CIVILIAN,
        KILLER,
        SHERIFF,
    }

    private static final Map<ServerLevel, RoleState> instances = new HashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutRoleManager");

    private static RoleState getOrCreate(ServerLevel level) {
        return instances.computeIfAbsent(level, k -> new RoleState());
    }

    private static class RoleState {
        final Map<UUID, RoleType> roles = new HashMap<>();
        final Map<UUID, Faction> factions = new HashMap<>();
        final Set<UUID> sheriffs = new HashSet<>();
    }

    public static void assignRole(ServerLevel level, UUID playerId, RoleType role, Faction faction) {
        var state = getOrCreate(level);
        state.roles.put(playerId, role);
        state.factions.put(playerId, faction);
    }

    public static Faction getFaction(ServerLevel level, UUID playerId) {
        var state = getOrCreate(level);
        return state.factions.getOrDefault(playerId, Faction.GOOD);
    }

    public static RoleType getRole(ServerLevel level, UUID playerId) {
        var state = getOrCreate(level);
        return state.roles.getOrDefault(playerId, RoleType.CIVILIAN);
    }

    public static boolean isAlive(ServerLevel level, UUID playerId) {
        return getOrCreate(level).roles.containsKey(playerId);
    }

    public static void eliminate(ServerLevel level, UUID playerId) {
        var state = getOrCreate(level);
        state.roles.remove(playerId);
        state.factions.remove(playerId);
        state.sheriffs.remove(playerId);
    }

    // ====== 警长 ======

    public static void setSheriff(ServerLevel level, UUID playerId) {
        var state = getOrCreate(level);
        state.sheriffs.add(playerId);
        state.roles.put(playerId, RoleType.SHERIFF);
    }

    public static boolean isSheriff(ServerLevel level, UUID playerId) {
        return getOrCreate(level).sheriffs.contains(playerId);
    }

    public static int getSheriffCount(ServerLevel level) {
        return getOrCreate(level).sheriffs.size();
    }

    public static List<UUID> getAllSheriffs(ServerLevel level) {
        return new ArrayList<>(getOrCreate(level).sheriffs);
    }

    public static void assignSheriffs(ServerLevel level) {
        var state = getOrCreate(level);
        if (!state.sheriffs.isEmpty()) return;
        int killerCount = getRemainingBad(level);
        int sheriffCount = Math.max(1, killerCount);

        List<UUID> candidates = state.roles.entrySet().stream()
                .filter(e -> e.getValue() == RoleType.CIVILIAN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        Collections.shuffle(candidates);

        int assigned = 0;
        for (UUID id : candidates) {
            if (assigned >= sheriffCount) break;
            setSheriff(level, id);
            assigned++;
        }
        LOGGER.info("BlackoutRoleManager: Assigned {} SHERIFF(s) ({} killers, {} candidates)",
                assigned, killerCount, candidates.size());
    }

    // ====== 阵营统计 ======

    public static int getRemainingCount(ServerLevel level, Faction faction) {
        var state = getOrCreate(level);
        return (int) state.factions.values().stream().filter(f -> f == faction).count();
    }

    public static int getRemainingGood(ServerLevel level) { return getRemainingCount(level, Faction.GOOD); }
    public static int getRemainingBad(ServerLevel level) { return getRemainingCount(level, Faction.BAD); }

    public static List<UUID> getAllAlive(ServerLevel level) {
        return new ArrayList<>(getOrCreate(level).roles.keySet());
    }

    public static void clear(ServerLevel level) {
        instances.remove(level);
    }

    public static void initRandomAssignment(ServerLevel level, List<ServerPlayer> players) {
        clear(level);
        var state = getOrCreate(level);
        List<ServerPlayer> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);

        int killerCount = Math.max(1, (int) Math.ceil(shuffled.size() / 6.0));

        for (int i = 0; i < shuffled.size(); i++) {
            UUID id = shuffled.get(i).getUUID();
            if (i < killerCount) {
                state.roles.put(id, RoleType.KILLER);
                state.factions.put(id, Faction.BAD);
            } else {
                state.roles.put(id, RoleType.CIVILIAN);
                state.factions.put(id, Faction.GOOD);
            }
        }
        LOGGER.info("BlackoutRoleManager: Assigned {} KILLER / {} CIVILIAN ({} players, formula n/6 ceil)",
                killerCount, shuffled.size() - killerCount, shuffled.size());
    }
}
