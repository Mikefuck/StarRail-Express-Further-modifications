package com.habitrain.core.game.sre.role.skill;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.NecromancerReviveSupport;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import com.habitrain.core.game.sre.roleoverride.SreRolePoolFilter;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.api.replay.GameReplayUtils;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.init.RoleInitialItems;
import org.agmas.noellesroles.utils.RoleUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mike 主动技能「代码修改」：花费 400 金，强制准星玩家随机转职（本局启用池）。
 */
public final class MikeCodeEditSkill {
    public static final int COST = 400;
    public static final double RANGE = 6.0;

    private MikeCodeEditSkill() {}

    public static boolean use(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!HabiRoles.isHabiRole(self, HabiRoles.MIKE)) return false;

        ServerPlayer target = resolveTarget(self, ctx.target());
        if (target == null || target.getUUID().equals(self.getUUID()) || target.isSpectator()) {
            self.displayClientMessage(Component.literal("§c[代码修改] 未找到有效目标"), true);
            return false;
        }
        if (!GameUtils.isPlayerAliveAndSurvival(target)) {
            self.displayClientMessage(Component.literal("§c[代码修改] 目标不可用"), true);
            return false;
        }

        SREPlayerShopComponent shop;
        try {
            shop = SREPlayerShopComponent.KEY.get(self);
        } catch (Throwable t) {
            shop = null;
        }
        if (shop == null || shop.balance < COST) {
            self.displayClientMessage(Component.literal("§c[代码修改] 金币不足，需要 " + COST), true);
            return false;
        }

        SRERole current = null;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(self.level());
            if (game != null) {
                current = game.getRole(target);
            }
        } catch (Throwable ignored) {}

        List<SRERole> pool = buildPool(current);
        if (pool.isEmpty()) {
            self.displayClientMessage(Component.literal("§c[代码修改] 无可用职业"), true);
            return false;
        }

        SRERole next = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        if (next == null) {
            self.displayClientMessage(Component.literal("§c[代码修改] 无可用职业"), true);
            return false;
        }

        // 校验通过后再扣费 / 改职
        shop.addToBalance(-COST);
        // 不在转职前清包：reassignRole 失败时物品无法恢复（catch 块仅退款），
        // 成功路径下方本就会再清一次并按新职业发初始物（review M4）。

        try {
            if (target.level() instanceof ServerLevel level) {
                // 统一转职入口（替代原 RoleUtils.changeRole + reassignRole 双调用，消除双重 ModdedRoleAssigned）：
                // record=false：时间线不写默认「职业从 A 切换到 B」，改记自定义文案；
                // addStats=true；faction 传 null 由 resolveFactionFromSreRole 推导，
                // 否则独立罪/共享罪会被错误压成 GOOD/BAD。
                BlackoutRoleManager.reassignRole(level, target.getUUID(), next, null, false, true);
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("[Mike] changeRole failed for {}", target.getName().getString(), t);
            // 尽量退款，避免吞金
            try {
                shop.addToBalance(COST);
            } catch (Throwable ignored) {}
            self.displayClientMessage(Component.literal("§c[代码修改] 转职失败"), true);
            return false;
        }

        if (target.level() instanceof ServerLevel levelForNecro) {
            try {
                NecromancerReviveSupport.onKillerConvertedAway(levelForNecro, current, next);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Mike] necromancer revive credit failed", t);
            }
        }

        // 统一后仅 reassignRole 触发一次 ModdedRoleAssigned（组件 init）；此处仍清一次再按目标角色发初始物
        clearAllItems(target);
        try {
            RoleInitialItems.addInitialItemsForRole(target, next);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Mike] addInitialItems failed for {}", target.getName().getString(), t);
        }

        try {
            RoleUtils.sendWelcomeAnnouncement(target);
        } catch (Throwable ignored) {}

        recordMikeCodeEditReplay(self, target, current, next);
        playCodeEditSoundToAll(self);

        String nextName = roleDisplayName(next);
        self.displayClientMessage(
                Component.literal("§a[代码修改] 已将 " + target.getName().getString() + " 改为 " + nextName),
                true);
        target.displayClientMessage(
                Component.literal("§e[代码修改] 你的职业被强制改写为 " + nextName),
                true);
        HabiTrainCore.LOGGER.info("[Mike] {} code-edited {} -> {}",
                self.getName().getString(), target.getName().getString(), next.identifier());
        return true;
    }

    /**
     * 回放时间线：Mike 将 xxx 的职业从 旧身份 修改为了 新身份。
     * <p>注意：必须在调用前传入改职前的 {@code oldRole}。不要用
     * {@code getReplayPlayerDisplayText(target)} 附带职业——改职后它会读到新身份，
     * 导致「从新→新」。
     */
    private static void recordMikeCodeEditReplay(ServerPlayer caster, ServerPlayer target,
                                                 SRERole oldRole, SRERole next) {
        try {
            Component casterName = plainReplayPlayerName(caster);
            Component targetName = plainReplayPlayerName(target);
            Component oldRoleName = roleNameComponent(oldRole);
            Component newRoleName = roleNameComponent(next);
            Component msg = Component.translatable(
                    "replay.event.habitrain_core.mike_code_edit",
                    casterName,
                    targetName,
                    oldRoleName,
                    newRoleName
            );
            SRE.REPLAY_MANAGER.recordCustomEvent(msg);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Mike] failed to record custom replay event", t);
            try {
                SRE.REPLAY_MANAGER.recordCustomEvent(Component.literal(
                        "§b" + caster.getName().getString()
                                + " 将 " + target.getName().getString()
                                + " 的职业从 " + roleDisplayName(oldRole)
                                + " 修改为了 " + roleDisplayName(next)
                ));
            } catch (Throwable ignored) {}
        }
    }

    /** 仅玩家名（可带回放着色），不附带当前/快照职业后缀。 */
    private static Component plainReplayPlayerName(ServerPlayer player) {
        if (player == null) {
            return Component.literal("unknown");
        }
        try {
            return SRE.REPLAY_MANAGER.getPlayerName(player.getUUID()).copy();
        } catch (Throwable ignored) {
            return player.getName().copy();
        }
    }

    private static Component roleNameComponent(SRERole role) {
        if (role != null && role.identifier() != null) {
            try {
                return GameReplayUtils.getRoleNameWithSourceTMMColor(role.identifier().toString());
            } catch (Throwable ignored) {
                try {
                    return RoleUtils.getRoleName(role);
                } catch (Throwable ignored2) {
                    return Component.literal(role.identifier().getPath());
                }
            }
        }
        return Component.literal("unknown");
    }

    /** 全服玩家播放代码修改音效（不限距离）。 */
    private static void playCodeEditSoundToAll(ServerPlayer source) {
        if (source == null || source.getServer() == null) return;
        try {
            for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
                p.playNotifySound(HabiTrainCore.MIKE_CODE_EDIT_SOUND, SoundSource.PLAYERS, 1.0f, 1.0f);
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Mike] failed to play code-edit sound", t);
        }
    }

    private static String roleDisplayName(SRERole role) {
        if (role == null || role.identifier() == null) return "?";
        try {
            return RoleUtils.getRoleName(role).getString();
        } catch (Throwable ignored) {
            return role.identifier().getPath();
        }
    }

    private static List<SRERole> buildPool(SRERole current) {
        List<SRERole> pool = new ArrayList<>();
        for (SRERole role :
                com.habitrain.core.role.catalog.RoleCatalogConsumer.visiblePool()) {
            if (role == null) continue;
            if (!SreRolePoolFilter.isCurrentModeRandomizable(role)) continue;
            if (SevenSins.isSin(role)) continue; // 排除七宗罪：互斥仅开局执行，局中转罪会绕开一局一罪（review M5）
            if (current != null && role.equals(current)) continue;
            pool.add(role);
        }
        SreRolePoolFilter.warnIfLeaky("MikeCodeEdit", pool);
        return pool;
    }

    private static void clearAllItems(ServerPlayer target) {
        if (target == null) return;
        try {
            target.getInventory().clearContent();
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Mike] clearContent failed for {}", target.getName().getString(), t);
        }
    }

    /** 优先客户端上报 UUID；否则视线锥内最近玩家（对齐卖花女）。 */
    private static ServerPlayer resolveTarget(ServerPlayer self, UUID targetId) {
        if (targetId != null) {
            Player p = self.level().getPlayerByUUID(targetId);
            if (p instanceof ServerPlayer sp) return sp;
        }
        Vec3 eye = self.getEyePosition();
        Vec3 look = self.getLookAngle();
        double range = RANGE;
        AABB box = self.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        ServerPlayer best = null;
        double bestDist = range * range;
        for (Player p : self.level().getEntitiesOfClass(Player.class, box)) {
            if (p == self || p.isSpectator()) continue;
            if (!(p instanceof ServerPlayer sp)) continue;
            Vec3 to = p.getEyePosition().subtract(eye);
            double proj = to.dot(look);
            if (proj <= 0 || proj > range) continue;
            double distSq = to.lengthSqr();
            if (distSq < bestDist) {
                bestDist = distSq;
                best = sp;
            }
        }
        return best;
    }
}