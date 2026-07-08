package com.habitrain.core.game.blackout;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.network.BlackoutAnnouncePayload;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * 局未开始前电话不可用（check {@link #isPhoneUnlocked} 需先调用 reset 设置开始时间）。
 */
public final class BlackoutPoliceHireService {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutPoliceHireService");

    private static final int UNLOCK_SECONDS = 120;
    private static final int HIRE_COST = 300;

    private static final ConcurrentMap<ResourceKey<Level>, HireState> STATES = new ConcurrentHashMap<>();

    private BlackoutPoliceHireService() {}

    private static final class HireState {
        long gameStartTick = 0;
        final Set<UUID> hasHired = ConcurrentHashMap.newKeySet();
    }

    private static HireState getOrCreate(ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), k -> new HireState());
    }

    /** 对局开始时调用，记录开始时间 */
    public static void reset(ServerLevel level) {
        HireState state = new HireState();
        state.gameStartTick = level.getGameTime();
        STATES.put(level.dimension(), state);
        LOGGER.info("[PoliceHire] reset for {}, startTick={}", level.dimension().location(), state.gameStartTick);
    }

    /** 对局清理时调用，移除状态 */
    public static void cleanup(ServerLevel level) {
        STATES.remove(level.dimension());
        LOGGER.info("[PoliceHire] cleanup for {}", level.dimension().location());
    }

    /** 电话是否已解锁（开局后 120 秒） */
    public static boolean isPhoneUnlocked(ServerLevel level) {
        HireState state = STATES.get(level.dimension());
        if (state == null) return false;
        return level.getGameTime() - state.gameStartTick >= UNLOCK_SECONDS * 20L;
    }

    /** 返回剩余解锁秒数（负数表示已解锁） */
    public static int getRemainingLockSeconds(ServerLevel level) {
        HireState state = STATES.get(level.dimension());
        if (state == null) return UNLOCK_SECONDS;
        long elapsed = (level.getGameTime() - state.gameStartTick) / 20;
        return (int) Math.max(0, UNLOCK_SECONDS - elapsed);
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
        if (gameMode.isEmpty() || !"habitrains:blackout".equals(gameMode.get().getId())) {
            return Component.literal("§c当前不在停电对局中");
        }

        HireState state = STATES.get(level.dimension());
        if (state == null) {
            return Component.literal("§c电话系统尚未就绪");
        }

        // 2. 是否解锁
        if (!isPhoneUnlocked(level)) {
            return Component.literal("§c报警线路尚未接通（剩余 " + getRemainingLockSeconds(level) + " 秒）");
        }

        // 3. 本局已雇佣
        if (state.hasHired.contains(initiator.getUUID())) {
            return Component.literal("§c你本局已经拨打过110");
        }

        // 4. 金币余额
        var shop = SREPlayerShopComponent.KEY.get(initiator);
        if (shop == null || shop.balance < HIRE_COST) {
            return Component.literal("§c话费不足，需要 " + HIRE_COST + " 金币");
        }

        // 5. 杀手阵营人数
        int killerCount = BlackoutRoleManager.getRemainingBad(level);
        if (killerCount <= 0) {
            return Component.literal("§c当前没有杀手，无需聘请警察");
        }

        // 6. 警察不超杀手
        int sheriffCount = BlackoutRoleManager.getSheriffCount(level);
        if (sheriffCount + 1 > killerCount) {
            return Component.literal("§c当前警力已足够，无法继续聘请");
        }

        // 7. 随机警察职业
        Random random = new Random(level.getRandom().nextLong());
        var policeRole = BlackoutRoleManager.getRandomPoliceRole(random);
        if (policeRole == null) {
            return Component.literal("§c当前警察职业池为空");
        }

        // 8. 候选好人
        UUID targetId = BlackoutRoleManager.getRandomGoodNonSheriff(level, random);
        if (targetId == null) {
            return Component.literal("§c当前没有可转职的好人");
        }

        // ===== 成功流程 =====
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(targetId);
        if (target == null) {
            return Component.literal("§c目标玩家已离线");
        }

        // 扣金币
        shop.addToBalance(-HIRE_COST);

        // 标记已雇佣
        state.hasHired.add(initiator.getUUID());

        // 转职
        BlackoutRoleManager.setSheriff(level, targetId, policeRole, null);

        // 给目标发送职业介绍
        ServerPlayNetworking.send(target, new BlackoutAnnouncePayload(
                policeRole.getName().getString(),
                policeRole.getDescription().getString(),
                policeRole.getGoal().getString(),
                BlackoutRoleManager.getRemainingBad(level),
                BlackoutRoleManager.getRemainingGood(level)
        ));

        // 全图顶部通报
        String initName = initiator.getName().getString();
        String targetName = target.getName().getString();
        Component notify = Component.literal("§e收到 §b" + initName + " §e举报，§b" + targetName + " §e警长前来调查");
        for (ServerPlayer player : level.players()) {
            SubtitleNotifier.sendTop(player, Component.empty(), notify, 80);
        }

        LOGGER.info("[PoliceHire] {} hired police -> {} (role={})", initName, targetName,
                policeRole.getIdentifier());

        return null; // success
    }
}
