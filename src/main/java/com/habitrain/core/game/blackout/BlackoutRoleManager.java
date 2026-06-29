package com.habitrain.core.game.blackout;

import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停电模式 — 阵营/职业管理器。
 * 预留扩展接口：后续添加新角色只需新增 enum 值 + 修改分配逻辑。
 */
public class BlackoutRoleManager {

    public enum Faction {
        GOOD,   // 好人阵营
        BAD     // 坏人阵营
    }

    public enum RoleType {
        CIVILIAN,  // 平民
        KILLER,    // 杀手
        SHERIFF,   // 警长（投票选出）
        // 预留扩展: FUTURE_ROLE_1, FUTURE_ROLE_2
    }

    private static final Map<UUID, RoleType> ROLES = new HashMap<>();
    private static final Map<UUID, Faction> FACTIONS = new HashMap<>();
    private static UUID sheriffId = null;

    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutRoleManager");

    public static void assignRole(UUID playerId, RoleType role, Faction faction) {
        ROLES.put(playerId, role);
        FACTIONS.put(playerId, faction);
    }

    public static Faction getFaction(UUID playerId) {
        return FACTIONS.getOrDefault(playerId, Faction.GOOD);
    }

    public static RoleType getRole(UUID playerId) {
        return ROLES.getOrDefault(playerId, RoleType.CIVILIAN);
    }

    public static boolean isAlive(UUID playerId) {
        return ROLES.containsKey(playerId);
    }

    /** 淘汰玩家 (被枪击杀等) */
    public static void eliminate(UUID playerId) {
        ROLES.remove(playerId);
        FACTIONS.remove(playerId);
        if (playerId.equals(sheriffId)) sheriffId = null;
    }

    // ====== 警长 ======

    public static void setSheriff(UUID playerId) {
        sheriffId = playerId;
        ROLES.put(playerId, RoleType.SHERIFF);
    }

    public static UUID getSheriff() { return sheriffId; }

    public static boolean isSheriff(UUID playerId) {
        return playerId.equals(sheriffId);
    }

    /** 非杀手可当选警长 */
    public static boolean canBeSheriff(UUID playerId) {
        return isAlive(playerId) && getRole(playerId) != RoleType.KILLER;
    }

    // ====== 阵营统计 ======

    public static int getRemainingCount(Faction faction) {
        return (int) FACTIONS.values().stream().filter(f -> f == faction).count();
    }

    public static int getRemainingGood() { return getRemainingCount(Faction.GOOD); }
    public static int getRemainingBad() { return getRemainingCount(Faction.BAD); }

    /** 获取所有存活玩家ID (排除已淘汰的) */
    public static List<UUID> getAllAlive() {
        return new ArrayList<>(ROLES.keySet());
    }

    /** 获取可被投票的玩家 (存活且非自己) */
    public static List<UUID> getVotablePlayers(UUID voterId) {
        return getAllAlive().stream()
                .filter(id -> !id.equals(voterId))
                .toList();
    }

    public static void clear() {
        ROLES.clear();
        FACTIONS.clear();
        sheriffId = null;
    }

    /**
     * 独立分配阵营 — 不再依赖 SRE 角色同步。
     * @param players  所有参与玩家列表
     */
    public static void initRandomAssignment(List<ServerPlayer> players) {
        clear();
        List<ServerPlayer> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);

        int killerCount = Math.max(1, (int) Math.ceil(shuffled.size() / 6.0));

        for (int i = 0; i < shuffled.size(); i++) {
            UUID id = shuffled.get(i).getUUID();
            if (i < killerCount) {
                assignRole(id, RoleType.KILLER, Faction.BAD);
            } else {
                assignRole(id, RoleType.CIVILIAN, Faction.GOOD);
            }
        }
        LOGGER.info("BlackoutRoleManager: Assigned {} KILLER / {} CIVILIAN ({} players, formula n/6 ceil)",
                killerCount, shuffled.size() - killerCount, shuffled.size());
    }
}
