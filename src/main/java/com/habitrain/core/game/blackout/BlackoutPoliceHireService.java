package com.habitrain.core.game.blackout;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.network.BlackoutAnnouncePayload;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 电话雇佣警察服务。
 *
 * 每局每个 {@link ServerLevel#dimension()} 隔离运行。
 * 无开局解锁 CD：对局运行中即可拨打。
 *
 * 候选 = 存活非警察（含杀手）。抽到好人 → 转警察 GOOD；抽到杀手 → 保留杀手身份，
 * 仅记入 sheriffs 特权集，并奖励 200 金 + 一次性手枪。
 */
public final class BlackoutPoliceHireService {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutPoliceHireService");

    private static final int HIRE_COST = 50;
    private static final int KILLER_HIT_REWARD = 200;
    private static final ResourceLocation ONCE_REVOLVER_ID =
            ResourceLocation.parse("noellesroles:once_revolver");

    private static final ConcurrentMap<ResourceKey<Level>, HireState> STATES = new ConcurrentHashMap<>();

    private BlackoutPoliceHireService() {}

    private static final class HireState {
        long gameStartTick = -1; // -1 = game not yet started; set by onGameStarted when SRE game becomes running
        final Set<UUID> hasHired = ConcurrentHashMap.newKeySet();
        /** 串行化同 dimension 内并发 tryHire，防止同 tick 突破 police≤killer */
        final Object hireLock = new Object();
    }

    private static HireState getOrCreate(ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), k -> new HireState());
    }

    /** 对局开始时调用，初始化状态（gameStartTick 由 onGameStarted 在 SRE game 实际运行时设置） */
    public static void reset(ServerLevel level) {
        STATES.put(level.dimension(), new HireState());
        LOGGER.info("[PoliceHire] reset for {}, waiting for game start", level.dimension().location());
    }

    /** SRE 游戏实际开始运行时调用，记录起始刻 */
    public static void onGameStarted(ServerLevel level) {
        HireState state = STATES.get(level.dimension());
        if (state != null) {
            state.gameStartTick = level.getGameTime();
            LOGGER.info("[PoliceHire] game start detected, recording startTick={}", state.gameStartTick);
        }
    }

    /** 对局清理时调用，移除状态 */
    public static void cleanup(ServerLevel level) {
        STATES.remove(level.dimension());
        LOGGER.info("[PoliceHire] cleanup for {}", level.dimension().location());
    }

    /** 电话是否可用：无开局 CD，只要本局 hire 状态已初始化即解锁。 */
    public static boolean isPhoneUnlocked(ServerLevel level) {
        return STATES.containsKey(level.dimension());
    }

    /** 剩余解锁秒数（已取消开局 CD，恒为 0）。 */
    public static int getRemainingLockSeconds(ServerLevel level) {
        return 0;
    }

    /** 发起者本局是否已雇佣过 */
    public static boolean hasHired(ServerLevel level, UUID playerId) {
        HireState state = STATES.get(level.dimension());
        return state != null && state.hasHired.contains(playerId);
    }

    /**
     * 尝试雇佣警察。
     * @return null 表示成功，非 null 为错误消息
     */
    @org.jetbrains.annotations.Nullable
    public static Component tryHire(ServerLevel level, ServerPlayer initiator) {
        // 1. 停电模式对局检查
        var sreGame = SREGameWorldComponent.KEY.get(level);
        if (sreGame == null || !sreGame.isRunning()) {
            return Component.literal("§c当前不在停电对局中");
        }
        // 1b. 必须是停电模式
        var gameMode = GameModeRegistry.getActiveForLevel(level);
        if (gameMode.isEmpty() || !"habitrain:blackout".equals(gameMode.get().getId())) {
            return Component.literal("§c当前不在停电对局中");
        }

        HireState state = STATES.get(level.dimension());
        if (state == null) {
            return Component.literal("§c电话系统尚未就绪");
        }

        // 1c. 发起者必须存活（旁观者/已淘汰不可雇）
        if (initiator.isSpectator() || !BlackoutRoleManager.isAlive(level, initiator.getUUID())) {
            return Component.literal("§c你已淘汰，无法拨打110");
        }

        // 串行化扣款/转职，避免同 tick 并发突破 police≤killer
        synchronized (state.hireLock) {
            // 2. 本局已雇佣
            if (state.hasHired.contains(initiator.getUUID())) {
                return Component.literal("§c你本局已经拨打过110");
            }

            // 3. 金币余额
            var shop = SREPlayerShopComponent.KEY.get(initiator);
            if (shop == null || shop.balance < HIRE_COST) {
                return Component.literal("§c话费不足，需要 " + HIRE_COST + " 金币");
            }

            // 4. 杀手阵营人数
            int killerCount = BlackoutRoleManager.getRemainingBad(level);
            if (killerCount <= 0) {
                return Component.literal("§c当前没有杀手，无需聘请警察");
            }

            // 5. 警察不超杀手（成功前再读一遍）
            int sheriffCount = BlackoutRoleManager.getSheriffCount(level);
            if (sheriffCount + 1 > killerCount) {
                return Component.literal("§c当前警力已足够，无法继续聘请");
            }

            // 6. 候选：存活非警察（含杀手）；排除发起者（禁自雇）
            Random random = new Random(level.getRandom().nextLong());
            UUID targetId = BlackoutRoleManager.getRandomHireTarget(level, random, initiator.getUUID());
            if (targetId == null) {
                return Component.literal("§c当前没有可调查的目标");
            }

            ServerPlayer target = level.getServer().getPlayerList().getPlayer(targetId);
            if (target == null) {
                return Component.literal("§c目标玩家已离线");
            }

            BlackoutRoleManager.Faction targetFaction = BlackoutRoleManager.getFaction(level, targetId);
            boolean targetIsKiller = targetFaction == BlackoutRoleManager.Faction.BAD;

            // 好人路径需要警察职业池；杀手路径不转职，无需职业
            io.wifi.starrailexpress.api.SRERole policeRole = null;
            if (!targetIsKiller) {
                policeRole = BlackoutRoleManager.getRandomPoliceRole(random);
                if (policeRole == null) {
                    return Component.literal("§c当前警察职业池为空");
                }
            }

            // ===== 成功流程 =====
            shop.addToBalance(-HIRE_COST);
            state.hasHired.add(initiator.getUUID());

            if (targetIsKiller) {
                // 杀手：保留原身份与 BAD 计数，仅加入 sheriffs 特权集
                BlackoutRoleManager.setSheriff(level, targetId);

                // 奖励 200 金
                var targetShop = SREPlayerShopComponent.KEY.get(target);
                if (targetShop != null) {
                    targetShop.addToBalance(KILLER_HIT_REWARD);
                }

                // 一次性手枪
                giveOnceRevolver(target);

                SubtitleNotifier.sendTop(target, Component.empty(),
                        Component.literal("§c你被举报调查，但身份未暴露。获得 " + KILLER_HIT_REWARD + " 金币与一次性手枪。"),
                        80);

                LOGGER.info("[PoliceHire] {} hired -> killer {} kept identity + once_revolver + {}",
                        initiator.getName().getString(), target.getName().getString(), KILLER_HIT_REWARD);
            } else {
                // 好人：转警察，强制 GOOD 阵营
                BlackoutRoleManager.setSheriff(level, targetId, policeRole, BlackoutRoleManager.Faction.GOOD);

                ServerPlayNetworking.send(target, new BlackoutAnnouncePayload(
                        policeRole.getName().getString(),
                        policeRole.getDescription().getString(),
                        policeRole.getGoal().getString(),
                        BlackoutRoleManager.getRemainingBad(level),
                        BlackoutRoleManager.getRemainingGood(level)
                ));

                LOGGER.info("[PoliceHire] {} hired police -> {} (role={})",
                        initiator.getName().getString(), target.getName().getString(),
                        policeRole.getIdentifier());
            }

            // 全图顶部通报（对外统一口径，不暴露杀手是否被抽中）
            String initName = initiator.getName().getString();
            String targetName = target.getName().getString();
            Component notify = Component.literal("§e收到 §b" + initName + " §e举报，§b" + targetName + " §e警长前来调查");
            for (ServerPlayer player : level.players()) {
                SubtitleNotifier.sendTop(player, Component.empty(), notify, 80);
            }

            return null; // success
        }
    }

    private static void giveOnceRevolver(ServerPlayer target) {
        var item = BuiltInRegistries.ITEM.get(ONCE_REVOLVER_ID);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            LOGGER.warn("[PoliceHire] noellesroles:once_revolver missing, skip gun grant for {} (gold reward still applied)",
                    target.getName().getString());
            return;
        }
        ItemStack gun = new ItemStack(item, 1);
        if (!target.getInventory().add(gun)) {
            target.drop(gun, false);
        }
    }
}
