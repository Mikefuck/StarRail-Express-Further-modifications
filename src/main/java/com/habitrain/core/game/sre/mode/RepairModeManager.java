package com.habitrain.core.game.sre.mode;

import com.habitrain.core.game.sre.rest.EliminatedRestAreaService;
import io.wifi.starrailexpress.cca.ExtraSlotComponent;
import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import com.habitrain.core.vote.OptionVoteManager;
import io.wifi.starrailexpress.cca.ParticipationComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 维修人员模式的服务端权威管理器。
 *
 * <p>维修员进入维修模式后：把自己标记为「不参与 SRE 对局」（{@link ParticipationComponent}），
 * 从而不计入人数统计、不进开局名单、不分配任务/角色；同时切换为创造模式以便修图。其锁定的
 * 地图将从 {@code ModeMapVoteOrchestrator} 的投票候选池中排除（见 {@link #isMapLocked}）；
 * 进入时还会删除维修员已投的票，并从进行中的地图投票候选里拿掉该图。
 *
 * <p>退出/断线/停服时恢复原参与状态与游戏模式，并释放对地图的锁。一张地图可被多位玩家同时
 * 锁定；当某地图不再有任何负责玩家时，它自动回到投票池。固定 SRE 上游是硬依赖；
 * 异常捕获用于运行时恢复，不能替代该依赖。</p>
 */
public final class RepairModeManager {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("habitrain_core|RepairModeManager");

    /** 玩家 UUID → 维修记录。 */
    private static final ConcurrentMap<UUID, RepairEntry> REPAIRS = new ConcurrentHashMap<>();

    /**
     * 对局开始/结束后待做「冒险 → 创造」切换的维修员：值为剩余步数
     * （{@link #REFRESH_TO_ADVENTURE}=下一 tick 切冒险，{@link #REFRESH_TO_CREATIVE}=再下一 tick 切回创造）。
     */
    private static final ConcurrentMap<UUID, Integer> GAME_MODE_REFRESH = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Boolean> REFRESH_WAS_FLYING = new ConcurrentHashMap<>();
    /** 离线期间被移除维修状态、待下次上线清理创造背包并恢复游戏模式的玩家快照。 */
    private static final ConcurrentMap<UUID, RepairEntry> PENDING_OFFLINE_EXIT_CLEANUP = new ConcurrentHashMap<>();
    private static final int REFRESH_TO_ADVENTURE = 2;
    private static final int REFRESH_TO_CREATIVE = 1;
    private static volatile boolean eventsRegistered = false;

    private RepairModeManager() {}

    /** 注册 SRE 对局开始/结束事件与离线重连清理：维修员各切一次冒险再切回创造。 */
    public static void registerEvents() {
        if (eventsRegistered) return;
        eventsRegistered = true;
        try {
            io.wifi.starrailexpress.event.OnGameStarted.EVENT.register(level -> scheduleGameModeRefresh());
            io.wifi.starrailexpress.event.OnGameEnd.EVENT.register((level, game) -> scheduleGameModeRefresh());
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                ServerPlayer player = handler.getPlayer();
                if (player == null) return;
                RepairEntry pending = PENDING_OFFLINE_EXIT_CLEANUP.remove(player.getUUID());
                if (pending != null) {
                    applyExitRestoreToPlayer(player, pending);
                    LOGGER.info("[RepairMode] applied deferred offline exit cleanup for {}", player.getUUID());
                }
            });
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
                GAME_MODE_REFRESH.clear();
                REFRESH_WAS_FLYING.clear();
                PENDING_OFFLINE_EXIT_CLEANUP.clear();
            });
        } catch (Throwable t) {
            LOGGER.error("[RepairMode] failed to register game start/end events", t);
        }
    }

    /** 给当前所有维修员排一次「冒险 → 创造」切换；在之后两个服务端 tick 内完成，避开 SRE 同 tick 的重置。 */
    public static void scheduleGameModeRefresh() {
        for (UUID uuid : REPAIRS.keySet()) {
            GAME_MODE_REFRESH.put(uuid, REFRESH_TO_ADVENTURE);
        }
    }

    /** 每个服务端 tick 推进一步待处理的切换（由 ModTickHandler 调用）。 */
    public static void tickGameModeRefresh(MinecraftServer server) {
        if (server == null || GAME_MODE_REFRESH.isEmpty()) return;
        for (UUID uuid : new ArrayList<>(GAME_MODE_REFRESH.keySet())) {
            Integer step = GAME_MODE_REFRESH.get(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            // 期间已退出维修模式或离线：exit 已恢复原模式，不再干预
            if (step == null || player == null || !isRepairer(uuid)) {
                GAME_MODE_REFRESH.remove(uuid);
                REFRESH_WAS_FLYING.remove(uuid);
                continue;
            }
            try {
                if (step == REFRESH_TO_ADVENTURE) {
                    boolean wasFlying = player.getAbilities().flying;
                    REFRESH_WAS_FLYING.put(uuid, wasFlying);
                    player.setGameMode(GameType.ADVENTURE);
                    if (wasFlying) {
                        player.getAbilities().mayfly = true;
                        player.getAbilities().flying = true;
                        player.onUpdateAbilities();
                    }
                    GAME_MODE_REFRESH.put(uuid, REFRESH_TO_CREATIVE);
                } else {
                    GAME_MODE_REFRESH.remove(uuid);
                    boolean wasFlying = Boolean.TRUE.equals(REFRESH_WAS_FLYING.remove(uuid));
                    player.setGameMode(GameType.CREATIVE);
                    if (wasFlying) {
                        player.getAbilities().mayfly = true;
                        player.getAbilities().flying = true;
                        player.onUpdateAbilities();
                    }
                }
            } catch (Throwable t) {
                GAME_MODE_REFRESH.remove(uuid);
                REFRESH_WAS_FLYING.remove(uuid);
                LOGGER.warn("[RepairMode] game mode refresh failed for {}", uuid, t);
                try {
                    player.setGameMode(GameType.CREATIVE);
                } catch (Throwable ignored) {
                    // 已记录上面的失败
                }
            }
        }
    }

    /** 单个维修记录：玩家名、锁定地图、进入前的参与状态、游戏模式与背包快照。 */
    private static final class RepairEntry {
        final String playerName;
        final String mapId;
        final boolean priorParticipating;
        final GameType priorGameType;
        final ResourceKey<Level> participationDimension;
        final long lockedAtMs;
        final InventorySnapshot inventorySnapshot;

        RepairEntry(String playerName, String mapId, boolean priorParticipating, GameType priorGameType,
                    ResourceKey<Level> participationDimension, InventorySnapshot inventorySnapshot) {
            this.playerName = playerName;
            this.mapId = mapId;
            this.priorParticipating = priorParticipating;
            this.priorGameType = priorGameType;
            this.participationDimension = participationDimension;
            this.lockedAtMs = System.currentTimeMillis();
            this.inventorySnapshot = inventorySnapshot;
        }
    }

    private static final class InventorySnapshot {
        final List<ItemStack> items;
        final List<ItemStack> armor;
        final List<ItemStack> offhand;
        final Map<ResourceLocation, ItemStack> extraSlots;
        final ItemStack carried;
        final List<MobEffectInstance> effects;

        private InventorySnapshot(List<ItemStack> items, List<ItemStack> armor, List<ItemStack> offhand,
                                  Map<ResourceLocation, ItemStack> extraSlots, ItemStack carried, List<MobEffectInstance> effects) {
            this.items = items;
            this.armor = armor;
            this.offhand = offhand;
            this.extraSlots = extraSlots;
            this.carried = carried;
            this.effects = effects;
        }

        static InventorySnapshot captureAndClear(ServerPlayer player) {
            Inventory inv = player.getInventory();
            List<ItemStack> items = new ArrayList<>(inv.items.size());
            for (ItemStack s : inv.items) {
                items.add(s == null ? ItemStack.EMPTY : s.copy());
            }
            List<ItemStack> armor = new ArrayList<>(inv.armor.size());
            for (ItemStack s : inv.armor) {
                armor.add(s == null ? ItemStack.EMPTY : s.copy());
            }
            List<ItemStack> offhand = new ArrayList<>(inv.offhand.size());
            for (ItemStack s : inv.offhand) {
                offhand.add(s == null ? ItemStack.EMPTY : s.copy());
            }
            Map<ResourceLocation, ItemStack> extra = new java.util.LinkedHashMap<>();
            ExtraSlotComponent extraComponent = ExtraSlotComponent.KEY.get(player);
            extraComponent.SLOTS.forEach((key, stack) -> {
                if (key != null && stack != null && !stack.isEmpty()) extra.put(key, stack.copy());
            });
            extraComponent.clear();
            ItemStack carried = player.containerMenu != null && !player.containerMenu.getCarried().isEmpty()
                    ? player.containerMenu.getCarried().copy()
                    : ItemStack.EMPTY;
            List<MobEffectInstance> effects = new ArrayList<>();
            for (MobEffectInstance effect : player.getActiveEffects()) {
                if (effect != null) {
                    effects.add(new MobEffectInstance(effect));
                }
            }
            if (player.containerMenu != null) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }
            inv.clearContent();
            player.removeAllEffects();
            player.inventoryMenu.broadcastChanges();
            return new InventorySnapshot(items, armor, offhand, extra, carried, effects);
        }

        void restore(ServerPlayer player) {
            clearPlayerInventoryAndEffects(player);
            if (SREGameModeBase.isAnySreGameStartingOrRunning(player.getServer())) {
                return;
            }
            Inventory inv = player.getInventory();
            for (int i = 0; i < Math.min(items.size(), inv.items.size()); i++) {
                inv.items.set(i, items.get(i).copy());
            }
            for (int i = 0; i < Math.min(armor.size(), inv.armor.size()); i++) {
                inv.armor.set(i, armor.get(i).copy());
            }
            for (int i = 0; i < Math.min(offhand.size(), inv.offhand.size()); i++) {
                inv.offhand.set(i, offhand.get(i).copy());
            }
            ExtraSlotComponent extraComponent = ExtraSlotComponent.KEY.get(player);
            extraSlots.forEach((key, stack) -> extraComponent.setItem(key, stack.copy()));
            if (player.containerMenu != null && !carried.isEmpty()) {
                player.containerMenu.setCarried(carried.copy());
            }
            for (MobEffectInstance effect : effects) {
                player.addEffect(new MobEffectInstance(effect));
            }
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static void clearPlayerInventoryAndEffects(ServerPlayer player) {
        if (player == null) return;
        try {
            if (player.containerMenu != null) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }
            player.getInventory().clearContent();
            ExtraSlotComponent.KEY.get(player).clear();
            player.removeAllEffects();
            player.inventoryMenu.broadcastChanges();
        } catch (Throwable t) {
            LOGGER.debug("[RepairMode] clearPlayerInventoryAndEffects failed for {}: {}", player.getUUID(), t.toString());
        }
    }

    private static void applyExitRestoreToPlayer(ServerPlayer player, RepairEntry entry) {
        if (player == null || entry == null) return;
        try {
            if (entry.inventorySnapshot != null) {
                entry.inventorySnapshot.restore(player);
            } else {
                clearPlayerInventoryAndEffects(player);
            }
            GameType targetMode = (entry.priorGameType == null
                    || entry.priorGameType == GameType.SPECTATOR
                    || entry.priorGameType == GameType.CREATIVE)
                    ? GameType.ADVENTURE
                    : entry.priorGameType;
            player.setGameMode(targetMode);
            com.habitrain.core.network.RepairModeSyncPayload.sendToPlayer(player, false);
        } catch (Throwable t) {
            LOGGER.warn("[RepairMode] applyExitRestoreToPlayer failed for {}", player.getUUID(), t);
        }
    }

    /**
     * 玩家进入维修模式并锁定一张地图。
     * prior 读取 / setParticipating(false) / 创造模式任一步失败则整段 abort，不登记维修员。
     * （需 ServerPlayer，纯单测无法覆盖完整 enter 路径。）
     */
    public static boolean enter(ServerPlayer player, String mapId) {
        if (player == null || mapId == null || mapId.isBlank()) return false;
        UUID uuid = player.getUUID();
        if (REPAIRS.containsKey(uuid)) return false; // 已在维修模式

        ServerLevel level = player.serverLevel();
        if (level == null) return false;

        // 拒绝在对局进行中或处于已淘汰/观战/休息区状态时进入维修模式
        if (SREModeStartAdapter.isSreGameBlocking(level)
                || SREGameModeBase.isAnySreGameStartingOrRunning(player.getServer())
                || EliminatedRestAreaService.isResting(player)
                || player.isSpectator()
                || isPlayerEliminatedSafe(player)) {
            LOGGER.warn("[RepairMode] refused enter for {}: match active or player eliminated/spectating", uuid);
            return false;
        }

        boolean priorParticipating;
        try {
            ParticipationComponent participation = ParticipationComponent.KEY.get(level);
            priorParticipating = participation.isParticipating(uuid);
        } catch (Throwable t) {
            LOGGER.error("[RepairMode] read prior participating failed for {}; refuse enter", uuid, t);
            return false;
        }
        GameType rawPriorGameType = player.gameMode.getGameModeForPlayer();
        GameType priorGameType = (rawPriorGameType == null
                || rawPriorGameType == GameType.SPECTATOR
                || rawPriorGameType == GameType.CREATIVE)
                ? GameType.ADVENTURE
                : rawPriorGameType;

        // 豁免：标记不参与对局（不计入人数/开局/任务/角色）
        try {
            ParticipationComponent.KEY.get(level).setParticipating(uuid, false);
        } catch (Throwable t) {
            LOGGER.error("[RepairMode] setParticipating(false) failed for {}; refuse enter", uuid, t);
            return false;
        }

        InventorySnapshot snapshot = InventorySnapshot.captureAndClear(player);

        // 创造模式便于修图；失败则恢复参与状态与背包，不登记维修员
        try {
            player.setGameMode(GameType.CREATIVE);
        } catch (Throwable t) {
            LOGGER.error("[RepairMode] setGameMode(CREATIVE) failed for {}; abort enter", uuid, t);
            try {
                snapshot.restore(player);
            } catch (Throwable ignored) {
            }
            try {
                ParticipationComponent.KEY.get(level).setParticipating(uuid, priorParticipating);
            } catch (Throwable restore) {
                LOGGER.error("[RepairMode] restore participating after creative failure failed for {}",
                        uuid, restore);
            }
            return false;
        }

        PENDING_OFFLINE_EXIT_CLEANUP.remove(uuid);
        REPAIRS.put(uuid, new RepairEntry(player.getGameProfile().getName(), mapId, priorParticipating,
                priorGameType, level.dimension(), snapshot));
        LOGGER.info("[RepairMode] {} entered repair mode, locking map={}", uuid, mapId);
        dropRepairerVoteAndLockedMap(player, mapId);
        // 同步到本机客户端：屏蔽开局黑场/转场与结尾动画
        com.habitrain.core.network.RepairModeSyncPayload.sendToPlayer(player, true);
        return true;
    }

    private static boolean isPlayerEliminatedSafe(ServerPlayer player) {
        try {
            return GameUtils.isPlayerEliminated(player);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 进维修后已投票作废，并把被锁图从进行中的地图投票候选里拿掉。 */
    private static void dropRepairerVoteAndLockedMap(ServerPlayer player, String mapId) {
        UUID uuid = player.getUUID();
        try {
            MinecraftServer server = player.getServer();
            if (server != null) {
                for (ServerLevel sl : server.getAllLevels()) {
                    OptionVoteManager.onVoterRemoved(sl, uuid);
                    OptionVoteManager.invalidateMapOption(sl, mapId);
                }
            } else if (player.serverLevel() != null) {
                OptionVoteManager.onVoterRemoved(player.serverLevel(), uuid);
                OptionVoteManager.invalidateMapOption(player.serverLevel(), mapId);
            }
        } catch (Throwable t) {
            LOGGER.warn("[RepairMode] failed to drop vote/map option for {} map={}", uuid, mapId, t);
        }
    }

    /** 玩家退出维修模式（cancel / remove 共用）。 */
    public static boolean exit(ServerPlayer player) {
        if (player == null) return false;
        return exit(player.getUUID(), player.serverLevel());
    }

    /** 按 UUID 退出（断线/停服时玩家可能不在线）。 */
    public static boolean exit(UUID uuid, ServerLevel level) {
        RepairEntry entry = REPAIRS.get(uuid);
        if (entry == null) return false;

        // 恢复原参与状态（不覆盖玩家原本的参与意愿）
        MinecraftServer server = level != null ? level.getServer() : null;
        if (!restoreParticipation(entry.participationDimension,
                key -> server == null ? null : server.getLevel(key),
                source -> ParticipationComponent.KEY.get(source).setParticipating(uuid, entry.priorParticipating))) {
            LOGGER.warn("[RepairMode] source world unavailable or restore failed for {}; retaining repair record dim={}",
                    uuid, entry.participationDimension.location());
            return false;
        }
        // 恢复原游戏模式与背包（在线立即执行；离线登记待下次登录清理创造背包并恢复模式）
        ServerPlayer online = server != null ? server.getPlayerList().getPlayer(uuid) : null;
        if (online != null) {
            try {
                if (entry.inventorySnapshot != null) {
                    entry.inventorySnapshot.restore(online);
                } else {
                    clearPlayerInventoryAndEffects(online);
                }
                online.setGameMode(entry.priorGameType);
            } catch (Throwable t) {
                LOGGER.warn("[RepairMode] restore gameMode failed for {}", uuid, t);
                return false;
            }
            // 同步到本机客户端：恢复开局黑场/转场与结尾动画
            com.habitrain.core.network.RepairModeSyncPayload.sendToPlayer(online, false);
        } else {
            PENDING_OFFLINE_EXIT_CLEANUP.put(uuid, entry);
        }
        REPAIRS.remove(uuid, entry);
        GAME_MODE_REFRESH.remove(uuid);
        REFRESH_WAS_FLYING.remove(uuid);
        LOGGER.info("[RepairMode] {} exited repair mode, released map={}", uuid, entry.mapId);
        return true;
    }

    /** 断线时自动解锁（由 DISCONNECT 事件调用）。
     *  服务器入口只用于解析记录中的来源维度。 */
    public static void onPlayerDisconnect(UUID uuid, MinecraftServer server) {
        if (uuid == null) return;
        ServerLevel level = (server != null) ? server.overworld() : null;
        exit(uuid, level);
    }

    /** 异常兜底：释放「玩家已离线但仍持锁」的记录（覆盖服务器崩溃/异常断线）。 */
    public static void checkAbnormal(MinecraftServer server) {
        if (server == null) return;
        for (UUID uuid : REPAIRS.keySet()) {
            if (server.getPlayerList().getPlayer(uuid) == null) {
                LOGGER.warn("[RepairMode] abnormal state: player {} offline but still holds repair lock; releasing", uuid);
                exit(uuid, server.overworld());
            }
        }
    }

    /** 停服清理：恢复所有维修员参与状态与模式并清空。
     *  遍历时先收集快照再逐个退出，避免 ConcurrentModificationException。 */
    public static void resetAll(MinecraftServer server) {
        List<UUID> all = new ArrayList<>(REPAIRS.keySet());
        ServerLevel level = (server != null) ? server.overworld() : null;
        for (UUID uuid : all) {
            exit(uuid, level);
        }
        GAME_MODE_REFRESH.clear();
        REFRESH_WAS_FLYING.clear();
    }

    public static boolean isRepairer(UUID uuid) {
        return uuid != null && REPAIRS.containsKey(uuid);
    }

    public static boolean isRepairer(ServerPlayer player) {
        return player != null && isRepairer(player.getUUID());
    }

    public static boolean isRepairer(Player player) {
        return player != null && isRepairer(player.getUUID());
    }

    /** 当前被锁定的地图集合。 */
    public static Set<String> getLockedMapIds() {
        Set<String> ids = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (RepairEntry e : REPAIRS.values()) {
            if (e.mapId != null && !e.mapId.isBlank()) ids.add(e.mapId);
        }
        return ids;
    }

    /** 某地图当前是否被任意维修员锁定（从投票池排除）。 */
    public static boolean isMapLocked(String mapId) {
        if (mapId == null || mapId.isBlank()) return false;
        for (RepairEntry e : REPAIRS.values()) {
            if (mapId.equals(e.mapId)) return true;
        }
        return false;
    }

    /** 强制解锁一张地图：移除该地图所有负责玩家并返回被移除的玩家数。 */
    public static int unlockMap(String mapId, MinecraftServer server) {
        if (mapId == null || mapId.isBlank()) return 0;
        int removed = 0;
        ServerLevel level = (server != null) ? server.overworld() : null;
        for (UUID uuid : new ArrayList<>(REPAIRS.keySet())) {
            RepairEntry e = REPAIRS.get(uuid);
            if (e != null && mapId.equals(e.mapId)) {
                if (exit(uuid, level)) removed++;
            }
        }
        return removed;
    }

    /** 维修记录快照（供 list 命令展示）。 */
    public static List<RepairEntryView> list() {
        List<RepairEntryView> out = new ArrayList<>();
        for (RepairEntry e : REPAIRS.values()) {
            out.add(new RepairEntryView(e.playerName, e.mapId, e.lockedAtMs));
        }
        return out;
    }

    /** list 命令的只读视图。 */
    public record RepairEntryView(String playerName, String mapId, long lockedAtMs) {}

    /** Resolves only the recorded source; missing worlds never fall back to the lobby. */
    static <W> boolean restoreParticipation(ResourceKey<Level> source,
            java.util.function.Function<ResourceKey<Level>, W> lookup,
            java.util.function.Consumer<W> restore) {
        try {
            W world = lookup.apply(source);
            if (world == null) return false;
            restore.accept(world);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[RepairMode] restore participation failed in {}", source, t);
            return false;
        }
    }
}
