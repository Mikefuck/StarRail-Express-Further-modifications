package com.habitrain.core.game.blackout;

import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Blackout mode role state, isolated per ServerLevel.
 *
 * Role identity is registry-backed so new roles can be added without changing
 * the manager's internal storage model.
 */
public class BlackoutRoleManager {

    public enum Faction {
        GOOD,
        BAD
    }

    private static final Map<ResourceKey<Level>, RoleState> INSTANCES = new HashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutRoleManager");

    private static RoleState getOrCreate(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level.dimension(), ignored -> new RoleState());
    }

    private static final class RoleState {
        final Map<UUID, ResourceLocation> roles = new HashMap<>();
        final Map<UUID, Faction> factions = new HashMap<>();
        final Set<UUID> sheriffs = new HashSet<>();
        // 全员角色历史：assignRole/setSheriff 写入，eliminate 不清除，
        // 供对局结束身份通报使用（被淘汰玩家也要在通报中显示原角色）。
        final Map<UUID, ResourceLocation> roleHistory = new HashMap<>();
    }

    public static void assignRole(ServerLevel level, UUID playerId, BlackoutRoleDefinition role) {
        if (role == null) {
            return;
        }
        assignRole(level, playerId, role.identifier(), role.faction());
    }

    public static void assignRole(ServerLevel level, UUID playerId, ResourceLocation roleId, Faction faction) {
        RoleState state = getOrCreate(level);
        state.roles.put(playerId, roleId);
        state.factions.put(playerId, faction);
        state.roleHistory.put(playerId, roleId);
    }

    public static Faction getFaction(ServerLevel level, UUID playerId) {
        return getOrCreate(level).factions.getOrDefault(playerId, Faction.GOOD);
    }

    public static ResourceLocation getRoleId(ServerLevel level, UUID playerId) {
        return getOrCreate(level).roles.getOrDefault(playerId, BlackoutRoles.CIVILIAN_ID);
    }

    public static BlackoutRoleDefinition getRoleDefinition(ServerLevel level, UUID playerId) {
        return BlackoutRoleRegistry.get(getRoleId(level, playerId)).orElse(null);
    }

    public static boolean isAlive(ServerLevel level, UUID playerId) {
        return getOrCreate(level).roles.containsKey(playerId);
    }

    public static void eliminate(ServerLevel level, UUID playerId) {
        RoleState state = getOrCreate(level);
        state.roles.remove(playerId);
        state.factions.remove(playerId);
        state.sheriffs.remove(playerId);
        BlackoutSheriffVoteManager.onPlayerRemoved(level, playerId);
    }

    public static void setSheriff(ServerLevel level, UUID playerId) {
        setSheriff(level, playerId, BlackoutRoles.SHERIFF, null);
    }

    /**
     * 投票选出的警长：把玩家的可见职业切换为 {@code role}（通常是随机警察职业），
     * 同时把玩家加入 {@code sheriffs} 集合以保留警长特权（/habi_api buy_gun、回放标识）。
     * <p>
     * 阵营处理：
     * <ul>
     *   <li>{@code factionOverride == null} → 使用 {@code role.faction()}（好人被票选时）</li>
     *   <li>{@code factionOverride != null} → 强制使用该阵营（杀手被票选时传 {@link Faction#BAD}
     *       以实现身份欺诈：显示成警察但实际仍是坏人阵营）</li>
     * </ul>
     * 无论哪种情况，roleHistory 记录的是实际分配的 roleId（用于结束通报）。
     *
     * @param role           被票选者要变成的停电角色（不能为 null）
     * @param factionOverride 阵营覆盖；null 表示沿用 role 的阵营
     */
    public static void setSheriff(ServerLevel level, UUID playerId, BlackoutRoleDefinition role,
                                  Faction factionOverride) {
        if (role == null) {
            role = BlackoutRoles.SHERIFF;
        }
        Faction faction = factionOverride != null ? factionOverride : role.faction();
        ResourceLocation roleId = role.identifier();

        RoleState state = getOrCreate(level);
        state.sheriffs.add(playerId);
        state.roles.put(playerId, roleId);
        state.factions.put(playerId, faction);
        state.roleHistory.put(playerId, roleId);

        var gameWorld = SREGameWorldComponent.KEY.get(level);
        if (gameWorld != null) {
            gameWorld.addRole(playerId, role.sreRole(), false);
            gameWorld.syncRoles();
            // 同步 SRE 回放快照，使结束通报中警长身份可被识别
            try {
                io.wifi.starrailexpress.SRE.REPLAY_MANAGER.updateRolesFromComponent(gameWorld);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 从警察职业池（{@link BlackoutRoles#POLICE_ROLE_IDS}）中随机一个已注册角色。
     * 警长投票选出的玩家会被切换成这个职业。若警察池为空（SRE 角色缺失），
     * 回退到 {@link BlackoutRoles#SHERIFF}。
     */
    @org.jetbrains.annotations.Nullable
    public static BlackoutRoleDefinition getRandomPoliceRole(java.util.Random random) {
        if (!BlackoutRoles.POLICE_ROLE_IDS.isEmpty()) {
            List<BlackoutRoleDefinition> candidates = BlackoutRoleRegistry.getByFaction(Faction.GOOD).stream()
                    .filter(def -> BlackoutRoles.POLICE_ROLE_IDS.contains(def.identifier()))
                    .toList();
            if (!candidates.isEmpty()) {
                return candidates.get(random.nextInt(candidates.size()));
            }
        }
        return BlackoutRoles.SHERIFF;
    }

    /**
     * 全员角色历史快照（含已淘汰玩家），用于对局结束身份通报。
     * 返回 playerId -> roleId 的映射。
     */
    public static Map<UUID, ResourceLocation> getRoleHistory(ServerLevel level) {
        return new HashMap<>(getOrCreate(level).roleHistory);
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

    public static int getRemainingCount(ServerLevel level, Faction faction) {
        return (int) getOrCreate(level).factions.values().stream()
                .filter(current -> current == faction)
                .count();
    }

    public static int getRemainingGood(ServerLevel level) {
        return getRemainingCount(level, Faction.GOOD);
    }

    public static int getRemainingBad(ServerLevel level) {
        return getRemainingCount(level, Faction.BAD);
    }

    public static List<UUID> getAllAlive(ServerLevel level) {
        return new ArrayList<>(getOrCreate(level).roles.keySet());
    }

    public static void clear(ServerLevel level) {
        INSTANCES.remove(level.dimension());
    }

    public static void initRandomAssignment(ServerLevel level, List<ServerPlayer> players) {
        clear(level);
        List<ServerPlayer> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        Random random = new Random(level.getRandom().nextLong());

        int killerCount = Math.max(1, (int) Math.ceil(shuffled.size() / 6.0));

        for (int i = 0; i < shuffled.size(); i++) {
            ServerPlayer p = shuffled.get(i);
            UUID id = p.getUUID();
            if (i < killerCount) {
                BlackoutRoleDefinition role = BlackoutRoleRegistry.getRandomByFaction(Faction.BAD, random);
                BlackoutRoleDefinition assigned = role != null ? role : BlackoutRoles.KILLER;
                assignRole(level, id, assigned);
                LOGGER.info("[BlackoutAssign] BAD slot {} -> player={} roleId={} displayName={} selectable={}",
                        i, p.getName().getString(), assigned.identifier(), assigned.displayName(),
                        assigned.selectableInRandomAssignment());
            } else {
                BlackoutRoleDefinition role = BlackoutRoleRegistry.getRandomByFaction(Faction.GOOD, random);
                BlackoutRoleDefinition assigned = role != null ? role : BlackoutRoles.CIVILIAN;
                assignRole(level, id, assigned);
                LOGGER.info("[BlackoutAssign] GOOD slot {} -> player={} roleId={} displayName={} selectable={}",
                        i, p.getName().getString(), assigned.identifier(), assigned.displayName(),
                        assigned.selectableInRandomAssignment());
            }
        }

        LOGGER.info("BlackoutRoleManager: Assigned {} BAD / {} GOOD ({} players, formula n/6 ceil)",
                killerCount, shuffled.size() - killerCount, shuffled.size());
        LOGGER.info("[BlackoutAssign] Registry GOOD selectable candidates: {}",
                BlackoutRoleRegistry.getByFaction(Faction.GOOD).stream()
                        .filter(BlackoutRoleDefinition::selectableInRandomAssignment)
                        .map(d -> d.identifier() + "=" + d.displayName())
                        .toList());
        LOGGER.info("[BlackoutAssign] Registry BAD selectable candidates: {}",
                BlackoutRoleRegistry.getByFaction(Faction.BAD).stream()
                        .filter(BlackoutRoleDefinition::selectableInRandomAssignment)
                        .map(d -> d.identifier() + "=" + d.displayName())
                        .toList());
    }
}
