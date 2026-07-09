package com.habitrain.core.game.blackout;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.agmas.harpymodloader.Harpymodloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 停电模式阵营状态管理器，按 ServerLevel 隔离。
 *
 * 角色分配完全复用 SRE 原版机制（SREMurderGameMode.assignRole → assignRolesToPlayers，
 * 含 RoleCountManager/权重/forced role），本类仅负责从 SRE 分配结果同步阵营状态
 * （canUseKiller=BAD, 其余=GOOD），并维护警长集合与角色历史用于回放结算。
 */
public class BlackoutRoleManager {

    public enum Faction {
        GOOD,
        BAD
    }

    private static final Map<ResourceKey<Level>, RoleState> INSTANCES = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutRoleManager");

    private static RoleState getOrCreate(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level.dimension(), ignored -> new RoleState());
    }

    private static final class RoleState {
        final Map<UUID, ResourceLocation> roles = new HashMap<>();
        final Map<UUID, Faction> factions = new HashMap<>();
        final Set<UUID> sheriffs = new HashSet<>();
        final Map<UUID, ResourceLocation> roleHistory = new HashMap<>();
        int initialGoodCount = 0;
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

    public static boolean isAlive(ServerLevel level, UUID playerId) {
        return getOrCreate(level).roles.containsKey(playerId);
    }

    public static void eliminate(ServerLevel level, UUID playerId) {
        RoleState state = getOrCreate(level);
        state.roles.remove(playerId);
        state.factions.remove(playerId);
        state.sheriffs.remove(playerId);
        BlackoutSheriffVoteManager.onPlayerRemoved(level, playerId);
        BlackoutExileVoteManager.onPlayerRemoved(level, playerId);
        BlackoutHornVoteHandler.onPlayerRemoved(playerId);
    }

    public static void setSheriff(ServerLevel level, UUID playerId) {
        RoleState state = getOrCreate(level);
        state.sheriffs.add(playerId);
    }

    /**
     * 投票选出的警长：把玩家的可见职业切换为 {@code sreRole}（通常是随机警察职业），
     * 同时把玩家加入 {@code sheriffs} 集合以保留警长特权（/habi_api buy_gun、回放标识）。
     *
     * @param sreRole         被票选者要变成的 SRE 原版角色（不能为 null）
     * @param factionOverride 阵营覆盖；null 表示沿用根据 sreRole 推导的阵营
     */
    public static void setSheriff(ServerLevel level, UUID playerId, SRERole sreRole,
                                  Faction factionOverride) {
        if (sreRole == null) {
            return;
        }
        Faction faction = factionOverride != null ? factionOverride
                : (sreRole.canUseKiller() ? Faction.BAD : Faction.GOOD);
        ResourceLocation roleId = sreRole.getIdentifier();

        RoleState state = getOrCreate(level);
        state.sheriffs.add(playerId);
        state.roles.put(playerId, roleId);
        state.factions.put(playerId, faction);
        state.roleHistory.put(playerId, roleId);

        var gameWorld = SREGameWorldComponent.KEY.get(level);
        if (gameWorld != null) {
            gameWorld.addRole(playerId, sreRole, false);
            gameWorld.syncRoles();
            try {
                io.wifi.starrailexpress.SRE.REPLAY_MANAGER.updateRolesFromComponent(gameWorld);
            } catch (Throwable ignored) {}

            // 触发 ModdedRoleAssigned.EVENT 发放该角色的初始物品（如武术教官的双截棍）
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(playerId);
            if (sp != null) {
                try {
                    org.agmas.harpymodloader.events.ModdedRoleAssigned.EVENT.invoker()
                            .assignModdedRole(sp, sreRole);
                } catch (Throwable t) {
                    LOGGER.error("setSheriff: failed to fire ModdedRoleAssigned for {}", playerId, t);
                }
            }
        }
    }

    /**
     * 从 SRE 原版警察职业池（isVigilanteTeam()=true）中随机一个角色。
     * 警长投票选出的玩家会被切换成这个职业。若警察池为空，返回 null。
     */
    @org.jetbrains.annotations.Nullable
    public static SRERole getRandomPoliceRole(Random random) {
        List<SRERole> police = TMMRoles.ROLES.values().stream()
                .filter(SRERole::isVigilanteTeam)
                .toList();
        return police.isEmpty() ? null : police.get(random.nextInt(police.size()));
    }

    /**
     * 全员角色历史快照（含已淘汰玩家），用于对局结束身份通报。
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

    public static int getInitialGoodCount(ServerLevel level) {
        return getOrCreate(level).initialGoodCount;
    }

    public static List<UUID> getAllAlive(ServerLevel level) {
        return new ArrayList<>(getOrCreate(level).roles.keySet());
    }

    /**
     * 电话雇佣候选：当前存活、非警察，可含好人与杀手。
     * 抽到杀手时由调用方保留杀手身份并发放奖励，不转职。
     * @param excludeId 可选，排除的 UUID（禁止自雇）；null 表示不排除
     */
    @org.jetbrains.annotations.Nullable
    public static UUID getRandomHireTarget(ServerLevel level, java.util.Random random,
                                           @org.jetbrains.annotations.Nullable UUID excludeId) {
        RoleState state = INSTANCES.get(level.dimension());
        if (state == null) return null;
        List<UUID> candidates = new ArrayList<>();
        for (UUID id : state.roles.keySet()) {
            if (excludeId != null && excludeId.equals(id)) continue;
            if (!state.sheriffs.contains(id)) {
                candidates.add(id);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    // ===== 警长角色禁用/恢复（修复 1：开局不分配警长阵营角色）=====
    private static final Set<ResourceLocation> disabledVigilanteRoles = new HashSet<>();

    /**
     * 禁用所有警长阵营角色（isVigilanteTeam()=true）。
     * 在 SREBlackoutGameMode.initializeGame 调用 assignRole 前执行，
     * 使 SRE 的角色分配系统不分配任何警察/警卫角色。
     * 投票选出警长后由 setSheriff 手动分配。
     */
    public static void disableAllVigilanteRoles() {
        disabledVigilanteRoles.clear();
        for (SRERole role : TMMRoles.ROLES.values()) {
            if (role.isVigilanteTeam()) {
                ResourceLocation id = role.getIdentifier();
                disabledVigilanteRoles.add(id);
                Harpymodloader.setRoleMaximum(id, 0);
            }
        }
        LOGGER.info("Disabled {} vigilante roles for blackout mode", disabledVigilanteRoles.size());
    }

    /**
     * 恢复之前禁用的警长角色配置。
     * 在 BlackoutMode.onCleanup 中调用，移除 ROLE_MAX 限制让后续对局恢复正常。
     */
    public static void restoreVigilanteRoleMaxes() {
        for (ResourceLocation id : disabledVigilanteRoles) {
            Harpymodloader.ROLE_MAX.remove(id);
        }
        int count = disabledVigilanteRoles.size();
        disabledVigilanteRoles.clear();
        if (count > 0) {
            LOGGER.info("Restored role max for {} vigilante roles", count);
        }
    }

    public static void clear(ServerLevel level) {
        INSTANCES.remove(level.dimension());
    }

    /**
     * 从 SRE 原版分配结果同步停电阵营状态。
     * 在 SREBlackoutGameMode.initializeGame 调用父类 assignRole 之后调用：
     * 遍历每个玩家的 SRE 角色，按 canUseKiller()=BAD / 其余=GOOD 写入阵营状态，
     * 并记录初始好人数供回放/任务进度使用。
     */
    public static void syncFactionsFromSreRoles(ServerLevel level, SREGameWorldComponent game,
                                                List<ServerPlayer> players) {
        RoleState state = getOrCreate(level);
        for (ServerPlayer p : players) {
            SRERole sreRole = game.getRole(p);
            if (sreRole == null) {
                continue;
            }
            ResourceLocation roleId = sreRole.getIdentifier();
            Faction faction = sreRole.canUseKiller() ? Faction.BAD : Faction.GOOD;
            state.roles.put(p.getUUID(), roleId);
            state.factions.put(p.getUUID(), faction);
            state.roleHistory.put(p.getUUID(), roleId);
        }
        state.initialGoodCount = getRemainingGood(level);
        LOGGER.info("[BlackoutSync] factions synced from SRE roles: {} players, initialGood={}",
                players.size(), state.initialGoodCount);
    }
}