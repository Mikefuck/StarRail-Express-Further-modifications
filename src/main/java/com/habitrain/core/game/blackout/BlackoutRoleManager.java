package com.habitrain.core.game.blackout;

import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import com.habitrain.core.game.sre.roleoverride.SreRolePoolFilter;
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
 * （七宗罪独立/分胜 + canUseKiller=BAD / 其余=GOOD），并维护警长集合与角色历史用于回放结算。
 *
 * <p>GOOD/BAD 仍是商店/任务/全灭判定用的阵营；{@link Faction#SIN_INDEPENDENT} /
 * {@link Faction#SIN_KILLER_SHARE} 不计入 getRemainingGood/Bad，也不走 GOOD/BAD 商店。
 */
public class BlackoutRoleManager {

    public enum Faction {
        GOOD,
        BAD,
        /** Independent sins: pride/greed/lust/sloth — alive but not in good/bad counts. */
        SIN_INDEPENDENT,
        /** Wrath — not in good/bad counts; shares personal win with killers. */
        SIN_KILLER_SHARE
    }

    private static final Map<ResourceKey<Level>, RoleState> INSTANCES = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutRoleManager");

    private static RoleState getOrCreate(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level.dimension(), ignored -> new RoleState());
    }

    private static final class RoleState {
        final Map<UUID, ResourceLocation> roles = new HashMap<>();
        final Map<UUID, Faction> factions = new HashMap<>();
        /** 淘汰后仍保留的阵营，供结算屏 setPlayerWin 使用。 */
        final Map<UUID, Faction> factionHistory = new HashMap<>();
        final Set<UUID> sheriffs = new HashSet<>();
        final Map<UUID, ResourceLocation> roleHistory = new HashMap<>();
        /**
         * 断线但仍在存活表内的玩家（宽限期内计存活，不可互动/不可被雇警抽中）。
         * 真正死亡/超时淘汰走 {@link #eliminate}，不走此集合。
         */
        final Set<UUID> offlinePlayers = ConcurrentHashMap.newKeySet();
        int initialGoodCount = 0;
    }

    public static void assignRole(ServerLevel level, UUID playerId, ResourceLocation roleId, Faction faction) {
        RoleState state = getOrCreate(level);
        state.roles.put(playerId, roleId);
        state.factions.put(playerId, faction);
        state.factionHistory.put(playerId, faction);
        state.roleHistory.put(playerId, roleId);
    }

    /**
     * 存活表阵营；未知/未入局 UUID 返回 {@code null}（不再默认 GOOD，避免局外玩家被当好人）。
     * 结算请用 {@link #getFactionForEnd}。
     */
    @org.jetbrains.annotations.Nullable
    public static Faction getFaction(ServerLevel level, UUID playerId) {
        return getOrCreate(level).factions.get(playerId);
    }

    /**
     * 结算用阵营：优先存活表，否则回落到淘汰前快照。
     * 避免 eliminate 后 getFaction 默认 GOOD 把死掉的杀手算成好人胜。
     */
    public static Faction getFactionForEnd(ServerLevel level, UUID playerId) {
        RoleState state = getOrCreate(level);
        Faction live = state.factions.get(playerId);
        if (live != null) return live;
        return state.factionHistory.getOrDefault(playerId, Faction.GOOD);
    }

    public static boolean isAlive(ServerLevel level, UUID playerId) {
        return getOrCreate(level).roles.containsKey(playerId);
    }

    /**
     * 断线标记：仍计存活（胜负人数），但不可互动、不作为雇警/放逐目标。
     * 与 {@link #eliminate}（真正淘汰）分离，避免瞬断被当死亡。
     */
    public static void markDisconnected(ServerLevel level, UUID playerId) {
        if (level == null || playerId == null) return;
        RoleState state = getOrCreate(level);
        if (!state.roles.containsKey(playerId)) return;
        state.offlinePlayers.add(playerId);
        // 进行中的投票/确认窗去掉断线者，但不改阵营存活表
        BlackoutExileVoteManager.onPlayerRemoved(level, playerId);
        BlackoutHornVoteHandler.onPlayerRemoved(playerId);
        LOGGER.info("markDisconnected: {} still alive offline in {}", playerId, level.dimension().location());
    }

    /** 重连：清除断线标记，恢复互动资格（前提仍 isAlive）。 */
    public static void clearDisconnected(ServerLevel level, UUID playerId) {
        if (level == null || playerId == null) return;
        if (getOrCreate(level).offlinePlayers.remove(playerId)) {
            LOGGER.info("clearDisconnected: {} reconnected in {}", playerId, level.dimension().location());
        }
    }

    public static boolean isDisconnected(ServerLevel level, UUID playerId) {
        if (level == null || playerId == null) return false;
        return getOrCreate(level).offlinePlayers.contains(playerId);
    }

    /** 在线且存活：电话/商店/放逐发起等互动门控。 */
    public static boolean isInteractable(ServerLevel level, UUID playerId) {
        return isAlive(level, playerId) && !isDisconnected(level, playerId);
    }

    public static void eliminate(ServerLevel level, UUID playerId) {
        RoleState state = getOrCreate(level);
        Faction faction = state.factions.get(playerId);
        if (faction != null) {
            state.factionHistory.put(playerId, faction);
        }
        state.roles.remove(playerId);
        state.factions.remove(playerId);
        state.sheriffs.remove(playerId);
        state.offlinePlayers.remove(playerId);
        BlackoutExileVoteManager.onPlayerRemoved(level, playerId);
        BlackoutHornVoteHandler.onPlayerRemoved(playerId);
    }

    /**
     * 复活：从 roleHistory/factionHistory 写回存活表，使电话/任务商店等 isAlive 门控重新通过。
     * 仅在明确复活入口调用；不自动复活。
     *
     * @return true 若成功写回（有历史记录）
     */
    public static boolean revive(ServerLevel level, UUID playerId) {
        if (level == null || playerId == null) return false;
        RoleState state = getOrCreate(level);
        if (state.roles.containsKey(playerId)) {
            return true; // 已存活
        }
        ResourceLocation roleId = state.roleHistory.get(playerId);
        Faction faction = state.factionHistory.get(playerId);
        if (roleId == null && faction == null) {
            LOGGER.warn("revive: no history for {}", playerId);
            return false;
        }
        if (faction == null) faction = Faction.GOOD;
        if (roleId != null) {
            state.roles.put(playerId, roleId);
        } else {
            // 无角色 id 时仍写入占位，保证 isAlive=true
            state.roles.put(playerId, ResourceLocation.parse("habitrain_core:revived"));
        }
        state.factions.put(playerId, faction);
        state.factionHistory.put(playerId, faction);
        if (roleId != null) {
            state.roleHistory.put(playerId, roleId);
        }
        LOGGER.info("revive: restored {} as {} / {}", playerId, roleId, faction);
        return true;
    }

    public static void setSheriff(ServerLevel level, UUID playerId) {
        RoleState state = getOrCreate(level);
        state.sheriffs.add(playerId);
    }

    /**
     * 从 SRE 角色推导停电阵营：七宗罪优先，否则 canUseKiller → BAD，其余 GOOD。
     */
    public static Faction resolveFactionFromSreRole(SRERole sreRole) {
        if (sreRole == null) {
            return Faction.GOOD;
        }
        if (SevenSins.isIndependentSin(sreRole)) {
            return Faction.SIN_INDEPENDENT;
        }
        if (SevenSins.isKillerShareSin(sreRole)) {
            return Faction.SIN_KILLER_SHARE;
        }
        return sreRole.canUseKiller() ? Faction.BAD : Faction.GOOD;
    }

    /**
     * Reversible data-only half of a role reassignment. It updates the SRE map
     * and Blackout's live role/faction tables but deliberately does not fire
     * compatibility events, update replay, or increment statistics. Those
     * effects belong to {@link #finishReassignRole} and must run only after the
     * caller has committed its full role-change transaction.
     */
    public static void prepareReassignRole(ServerLevel level, UUID playerId, SRERole sreRole,
                                           Faction factionOverride) {
        if (level == null || playerId == null || sreRole == null) {
            return;
        }
        Faction faction = factionOverride != null ? factionOverride : resolveFactionFromSreRole(sreRole);
        ResourceLocation roleId = sreRole.getIdentifier();
        if (roleId == null) {
            throw new IllegalArgumentException("reassigned role has no identifier");
        }
        RoleState state = getOrCreate(level);
        state.roles.put(playerId, roleId);
        state.factions.put(playerId, faction);
        state.factionHistory.put(playerId, faction);
        state.roleHistory.put(playerId, roleId);

        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(level);
        if (gameWorld != null) {
            gameWorld.addRole(playerId, sreRole, false);
        }
    }

    /**
     * Final, externally visible half of a reassignment. Call only after all
     * failure-prone internal state initialization has succeeded.
     */
    public static void finishReassignRole(ServerLevel level, UUID playerId, SRERole oldRole,
                                          SRERole newRole, boolean record, boolean addStats) {
        if (level == null || playerId == null || newRole == null) {
            return;
        }
        ServerPlayer player = level.getServer() == null ? null
                : level.getServer().getPlayerList().getPlayer(playerId);
        if (player != null && oldRole != null) {
            try {
                org.agmas.harpymodloader.events.ModdedRoleRemoved.EVENT.invoker()
                        .removeModdedRole(player, oldRole);
            } catch (Throwable t) {
                LOGGER.warn("finishReassignRole: ModdedRoleRemoved failed for {}", playerId, t);
            }
            if (record) {
                try {
                    io.wifi.starrailexpress.SRE.REPLAY_MANAGER
                            .recordPlayerRoleChange(playerId, oldRole, newRole);
                } catch (Throwable t) {
                    LOGGER.warn("finishReassignRole: recordPlayerRoleChange failed for {}", playerId, t);
                }
            }
        }
        if (addStats && player != null) {
            addReassignStats(player, newRole);
        }
        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(level);
        if (gameWorld != null) {
            gameWorld.syncRoles();
            try {
                io.wifi.starrailexpress.SRE.REPLAY_MANAGER.updateRolesFromComponent(gameWorld);
            } catch (Throwable ignored) {
            }
        }
        if (player != null) {
            try {
                org.agmas.harpymodloader.events.ModdedRoleAssigned.EVENT.invoker()
                        .assignModdedRole(player, newRole);
            } catch (Throwable t) {
                LOGGER.error("finishReassignRole: failed to fire ModdedRoleAssigned for {}", playerId, t);
            }
        }
    }

    private static void addReassignStats(ServerPlayer player, SRERole role) {
        try {
            var stats = io.wifi.starrailexpress.stats.PlayerStatsManager.get(player);
            stats.getOrCreateRoleStats(role.getIdentifier()).incrementTimesPlayed();
            if (role.isVigilanteTeam()) {
                stats.incrementTotalSheriffGames();
            } else if (role.canUseKiller()) {
                stats.incrementTotalKillerGames();
            } else if (role.isNeutrals()) {
                stats.incrementTotalNeutralGames();
            } else if (role.isInnocent() && !role.isVigilanteTeam()) {
                stats.incrementTotalCivilianGames();
            }
        } catch (Throwable t) {
            LOGGER.warn("finishReassignRole: stats update failed for {}", player.getUUID(), t);
        }
    }

    /**
     * 中途改职（非警长特权）：写 blackout 存活表/历史，并同步 SRE 角色。
     * 替罪羊转杀手等场景必须调用，否则 faction 仍停留在开局快照。
     *
     * @param factionOverride null 时按 {@link #resolveFactionFromSreRole(SRERole)} 推导
     */
    public static void reassignRole(ServerLevel level, UUID playerId, SRERole sreRole,
                                    Faction factionOverride) {
        reassignRole(level, playerId, sreRole, factionOverride, true, true);
    }

    /**
     * 统一转职入口：所有「把玩家变成另一个角色」的路径（替罪羊转杀手、Mike 代码修改、
     * 暴怒转职、警长选举）只走这里，替代上游 {@code RoleUtils.changeRole} 以避免
     * 双重 {@code ModdedRoleAssigned}（重复 init/初始物）。
     * <p>流程：旧角色清理（ModdedRoleRemoved，含上游精神病杀手清理）→ 时间线（可选）→
     * 统计（可选）→ 写 blackout 阵营表/历史 → 覆盖 SRE 角色并同步 → 单次 ModdedRoleAssigned。
     *
     * @param factionOverride null 时按 {@link #resolveFactionFromSreRole(SRERole)} 推导
     * @param record         是否记录「职业从 A 切换到 B」时间线（Mike/暴怒改记自定义文案，传 false）
     * @param addStats       是否计入角色/阵营场次统计
     */
    public static void reassignRole(ServerLevel level, UUID playerId, SRERole sreRole,
                                    Faction factionOverride, boolean record, boolean addStats) {
        if (level == null || playerId == null || sreRole == null) {
            return;
        }
        var gameWorld = SREGameWorldComponent.KEY.get(level);
        SRERole oldRole = gameWorld != null && gameWorld.getRoles() != null
                ? gameWorld.getRoles().get(playerId) : null;
        ServerPlayer sp = level.getServer().getPlayerList().getPlayer(playerId);

        // 旧角色清理 + 时间线（对齐原 RoleUtils.changeRole 的移除段）
        if (sp != null && oldRole != null) {
            try {
                org.agmas.harpymodloader.events.ModdedRoleRemoved.EVENT.invoker()
                        .removeModdedRole(sp, oldRole);
            } catch (Throwable t) {
                LOGGER.warn("reassignRole: ModdedRoleRemoved failed for {}", playerId, t);
            }
            if (record) {
                try {
                    io.wifi.starrailexpress.SRE.REPLAY_MANAGER
                            .recordPlayerRoleChange(playerId, oldRole, sreRole);
                } catch (Throwable t) {
                    LOGGER.warn("reassignRole: recordPlayerRoleChange failed for {}", playerId, t);
                }
            }
        }
        // 统计（对齐原 RoleUtils.changeRole 的 addStats 段）
        if (addStats && sp != null) {
            try {
                var stats = io.wifi.starrailexpress.stats.PlayerStatsManager.get(sp);
                stats.getOrCreateRoleStats(sreRole.getIdentifier()).incrementTimesPlayed();
                if (sreRole.isVigilanteTeam()) {
                    stats.incrementTotalSheriffGames();
                } else if (sreRole.canUseKiller()) {
                    stats.incrementTotalKillerGames();
                } else if (sreRole.isNeutrals()) {
                    stats.incrementTotalNeutralGames();
                } else if (sreRole.isInnocent() && !sreRole.isVigilanteTeam()) {
                    stats.incrementTotalCivilianGames();
                }
            } catch (Throwable t) {
                LOGGER.warn("reassignRole: stats update failed for {}", playerId, t);
            }
        }

        Faction faction = factionOverride != null ? factionOverride
                : resolveFactionFromSreRole(sreRole);
        ResourceLocation roleId = sreRole.getIdentifier();

        RoleState state = getOrCreate(level);
        state.roles.put(playerId, roleId);
        state.factions.put(playerId, faction);
        state.factionHistory.put(playerId, faction);
        state.roleHistory.put(playerId, roleId);

        if (gameWorld != null) {
            gameWorld.addRole(playerId, sreRole, false);
            gameWorld.syncRoles();
            try {
                io.wifi.starrailexpress.SRE.REPLAY_MANAGER.updateRolesFromComponent(gameWorld);
            } catch (Throwable ignored) {}

            if (sp != null) {
                try {
                    org.agmas.harpymodloader.events.ModdedRoleAssigned.EVENT.invoker()
                            .assignModdedRole(sp, sreRole);
                } catch (Throwable t) {
                    LOGGER.error("reassignRole: failed to fire ModdedRoleAssigned for {}", playerId, t);
                }
            }
        }
        LOGGER.info("reassignRole: {} -> {} / {}", playerId, roleId, faction);
    }

    /**
     * 投票选出的警长：把玩家的可见职业切换为 {@code sreRole}（通常是随机警察职业），
     * 同时把玩家加入 {@code sheriffs} 集合，供警长人数、电话候选与回放标识使用。
     *
     * @param sreRole         被票选者要变成的 SRE 原版角色（不能为 null）
     * @param factionOverride 阵营覆盖；null 表示沿用根据 sreRole 推导的阵营
     */
    public static void setSheriff(ServerLevel level, UUID playerId, SRERole sreRole,
                                  Faction factionOverride) {
        if (sreRole == null) {
            return;
        }
        RoleState state = getOrCreate(level);
        state.sheriffs.add(playerId);
        reassignRole(level, playerId, sreRole, factionOverride != null ? factionOverride
                : resolveFactionFromSreRole(sreRole));
        // reassignRole 不保证 sheriffs；上面已 add
        state.sheriffs.add(playerId);
    }

    /**
     * 从 SRE 原版警察职业池（isVigilanteTeam()=true）中随机一个<strong>无职业绑定</strong>的角色。
     * <p>
     * 中途电话聘请不会走开局 occupation 配对生成，绑定职业（如 JOJO↔DIO）
     * 单独 reassign 可能导致客户端掉线。因此排除：
     * <ul>
     *   <li>自身 {@code occupationRoles} 非空的主角色</li>
     *   <li>被任意角色列为 occupation companion 的伴生角色</li>
     *   <li>{@code occupiedRoleCount &gt; 1} 的多槽位角色</li>
     * </ul>
     * 若过滤后池为空，返回 null。
     */
    @org.jetbrains.annotations.Nullable
    public static SRERole getRandomPoliceRole(Random random) {
        Set<SRERole> occupationCompanions = new HashSet<>();
        List<SRERole> visibleRoles =
                com.habitrain.core.role.catalog.RoleCatalogConsumer.visiblePool();
        for (SRERole role : visibleRoles) {
            List<SRERole> companions = role.getoccupationRoles();
            if (companions != null && !companions.isEmpty()) {
                occupationCompanions.addAll(companions);
            }
        }

        List<SRERole> police = visibleRoles.stream()
                .filter(SreRolePoolFilter::isCurrentModeRandomizable)
                .filter(SRERole::isVigilanteTeam)
                .filter(role -> {
                    List<SRERole> own = role.getoccupationRoles();
                    return own == null || own.isEmpty();
                })
                .filter(role -> !occupationCompanions.contains(role))
                .filter(role -> role.getOccupiedRoleCount() <= 1)
                .toList();

        if (police.isEmpty()) {
            LOGGER.warn("getRandomPoliceRole: no standalone vigilante roles available "
                    + "(all filtered by occupation binding)");
            return null;
        }
        return police.get(random.nextInt(police.size()));
    }

    /**
     * 全员角色历史快照（含已淘汰玩家），用于对局结束身份通报。
     */
    public static Map<UUID, ResourceLocation> getRoleHistory(ServerLevel level) {
        return new HashMap<>(getOrCreate(level).roleHistory);
    }

    /**
     * 雇警上限用的「正牌警察」人数：仅统计仍为 GOOD 的 sheriffs。
     * 杀手被「点警」只拿特权枪/金，保留 BAD，不占 police≤killer 名额（C13）。
     */
    public static int getSheriffCount(ServerLevel level) {
        RoleState state = getOrCreate(level);
        int n = 0;
        for (UUID id : state.sheriffs) {
            if (state.factions.get(id) == Faction.GOOD) {
                n++;
            }
        }
        return n;
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
        return getRandomHireTarget(level, random, excludeId, false);
    }

    /** @param killersOnly 警长位已满时只抽杀手；杀手雇佣不占新的警长职业位。 */
    @org.jetbrains.annotations.Nullable
    public static UUID getRandomHireTarget(ServerLevel level, java.util.Random random,
                                           @org.jetbrains.annotations.Nullable UUID excludeId,
                                           boolean killersOnly) {
        RoleState state = INSTANCES.get(level.dimension());
        if (state == null) return null;
        List<UUID> candidates = new ArrayList<>();
        for (UUID id : state.roles.keySet()) {
            if (excludeId != null && excludeId.equals(id)) continue;
            if (state.offlinePlayers.contains(id)) continue; // 断线宽限内不可被抽中
            if (killersOnly && state.factions.get(id) != Faction.BAD) continue;
            if (!state.sheriffs.contains(id)) {
                candidates.add(id);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    // ROLE_MAX 是上游全局表；只允许在角色分配临界区内临时修改。
    private static final Object VIGILANTE_ASSIGN_LOCK = new Object();

    /**
     * 禁用所有警长阵营角色（isVigilanteTeam()=true）。
     * 在 SREBlackoutGameMode.initializeGame 调用 assignRole 前执行，
     * 使 SRE 的角色分配系统不分配任何警察/警卫角色。
     * 投票选出警长后由 setSheriff 手动分配。
     */
    public static void assignWithVigilantesDisabled(Runnable assignment) {
        if (assignment == null) return;
        synchronized (VIGILANTE_ASSIGN_LOCK) {
            Map<ResourceLocation, Integer> previous = new HashMap<>();
            Set<ResourceLocation> previouslyAbsent = new HashSet<>();
            for (SRERole role :
                    com.habitrain.core.role.catalog.RoleCatalogConsumer.visiblePool()) {
                if (!role.isVigilanteTeam()) continue;
                ResourceLocation id = role.getIdentifier();
                if (Harpymodloader.ROLE_MAX.containsKey(id)) {
                    previous.put(id, Harpymodloader.ROLE_MAX.get(id));
                } else {
                    previouslyAbsent.add(id);
                }
                Harpymodloader.setRoleMaximum(id, 0);
            }
            try {
                assignment.run();
            } finally {
                for (ResourceLocation id : previouslyAbsent) {
                    Harpymodloader.ROLE_MAX.remove(id);
                }
                for (Map.Entry<ResourceLocation, Integer> entry : previous.entrySet()) {
                    Harpymodloader.setRoleMaximum(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    public static void clear(ServerLevel level) {
        INSTANCES.remove(level.dimension());
    }

    /**
     * 从 SRE 原版分配结果同步停电阵营状态。
     * 在 SREBlackoutGameMode.initializeGame 调用父类 assignRole 之后调用：
     * 遍历每个玩家的 SRE 角色，按七宗罪分类 / canUseKiller 写入阵营状态，
     * 并记录初始好人数供回放/任务进度使用。
     *
     * <p>同步后每位已分配玩家都有显式 faction；{@link #getFaction} 对未同步玩家的
     * GOOD 默认仅作遗留兜底，不应再依赖。
     */
    public static void syncFactionsFromSreRoles(ServerLevel level, SREGameWorldComponent game,
                                                List<ServerPlayer> players) {
        RoleState state = getOrCreate(level);
        int sinIndependent = 0;
        int sinKillerShare = 0;
        for (ServerPlayer p : players) {
            SRERole sreRole = game.getRole(p);
            if (sreRole == null) {
                continue;
            }
            ResourceLocation roleId = sreRole.getIdentifier();
            Faction faction = resolveFactionFromSreRole(sreRole);
            if (faction == Faction.SIN_INDEPENDENT) sinIndependent++;
            else if (faction == Faction.SIN_KILLER_SHARE) sinKillerShare++;
            state.roles.put(p.getUUID(), roleId);
            state.factions.put(p.getUUID(), faction);
            state.factionHistory.put(p.getUUID(), faction);
            state.roleHistory.put(p.getUUID(), roleId);
        }
        state.initialGoodCount = getRemainingGood(level);
        LOGGER.info("[BlackoutSync] factions synced from SRE roles: {} players, initialGood={}, "
                        + "sinIndependent={}, sinKillerShare={}",
                players.size(), state.initialGoodCount, sinIndependent, sinKillerShare);
    }
}