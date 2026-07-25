package com.habitrain.core.game.sre.role.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoles;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.DynamicShopComponent;
import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.init.ModEffects;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 默剧杀手：人像默演、任务狂暴折扣、尸体 5 秒全隐（含骨架）。
 */
public final class MimeKillerComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<MimeKillerComponent> KEY = ComponentRegistry.getOrCreate(
            HabiTrainCore.id("mime_killer"), MimeKillerComponent.class);

    public static final int MIME_DURATION_SECONDS = 10;
    public static final int BASE_PSYCHO_PRICE = 500;
    public static final int PSYCHO_DISCOUNT_PER_TASK = 50;
    public static final int MAX_PLOT_THINK_BUYS = 2;
    public static final int BODY_HIDE_SECONDS = 5;

    private final Player player;
    private int psychoDiscount;
    private int plotThinkBuys;
    private final Map<UUID, Integer> rootedTicks = new HashMap<>();

    /**
     * 服务端隐藏尸体倒计时。渲染侧以实体 {@link Entity#isInvisible()} 为准（会同步到客户端）；
     * 此 map 只负责到期后恢复可见。
     */
    private static final Map<UUID, Integer> HIDDEN_BODIES = new HashMap<>();

    public MimeKillerComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public int getPsychoDiscount() {
        return psychoDiscount;
    }

    public int getPsychoPrice() {
        return Math.max(0, BASE_PSYCHO_PRICE - psychoDiscount);
    }

    public static ResourceLocation psychoItemId() {
        return BuiltInRegistries.ITEM.getKey(TMMItems.PSYCHO_MODE);
    }

    /**
     * 任务完成：折扣 +50，并写入 {@link DynamicShopComponent} 固定减价。
     * 商店 UI / tryBuy 都走 DynamicShop.effectivePrice，只改 MimeKillerComponent 不会显示也不会扣折扣价。
     */
    public void onTaskComplete() {
        psychoDiscount = Math.min(BASE_PSYCHO_PRICE, psychoDiscount + PSYCHO_DISCOUNT_PER_TASK);
        applyPsychoDynamicPrice();
        sync();
        HabiTrainCore.LOGGER.info("[MimeKiller] {} 任务完成，狂暴折扣={}，实际价={}",
                player.getName().getString(), psychoDiscount, getPsychoPrice());
    }

    public void resetPsychoDiscount() {
        psychoDiscount = 0;
        clearPsychoDynamicPrice();
        sync();
    }

    private void applyPsychoDynamicPrice() {
        try {
            ResourceLocation id = psychoItemId();
            if (id == null) return;
            DynamicShopComponent.KEY.maybeGet(player).ifPresent(dyn -> {
                if (psychoDiscount <= 0) {
                    dyn.clearModifier(id);
                } else {
                    // effective = base * 1.0 - flatReduction
                    dyn.setFlatReduction(id, psychoDiscount);
                }
            });
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[MimeKiller] 写入 DynamicShop 狂暴折扣失败", t);
        }
    }

    private void clearPsychoDynamicPrice() {
        try {
            ResourceLocation id = psychoItemId();
            if (id == null) return;
            DynamicShopComponent.KEY.maybeGet(player).ifPresent(dyn -> dyn.clearModifier(id));
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[MimeKiller] 清除 DynamicShop 狂暴折扣失败", t);
        }
    }

    public int getPlotThinkBuys() {
        return plotThinkBuys;
    }

    public boolean canBuyPlotThink() {
        return plotThinkBuys < MAX_PLOT_THINK_BUYS;
    }

    public void markPlotThinkBought() {
        plotThinkBuys++;
        sync();
    }

    /**
     * 隐藏尸体 5 秒：设实体 invisible（同步客户端）+ 服务端倒计时恢复。
     * <p>
     * 重要：{@code PlayerBodyEntity} 继承 {@link net.minecraft.world.entity.LivingEntity}，
     * 每 tick {@code updateInvisibilityStatus()} 会把无隐身药水的实体 {@code setInvisible(false)}。
     * 因此单次 setInvisible 不够——必须靠 {@code PlayerBodyEntityTickMixin} 在 tick 末尾 re-assert，
     * 以及本类 {@link #tickHiddenBodies} 双保险。客户端 {@code MimeBodyHideMixin} 在 isInvisible 时
     * 整段跳过渲染（含骨架）。
     */
    public static void hideBody(PlayerBodyEntity body, int ticks) {
        if (body == null) return;
        body.setInvisible(true);
        HIDDEN_BODIES.put(body.getUUID(), Math.max(1, ticks));
        HabiTrainCore.LOGGER.info("[MimeKiller] hide body {} for {} ticks", body.getUUID(), ticks);
    }

    /** @deprecated 用 {@link #hideBody(PlayerBodyEntity, int)}，需要实体才能 setInvisible */
    @Deprecated
    public static void hideBody(UUID bodyId, int ticks) {
        if (bodyId != null) {
            HIDDEN_BODIES.put(bodyId, Math.max(1, ticks));
        }
    }

    public static boolean isBodyHidden(UUID bodyId) {
        if (bodyId == null) return false;
        Integer t = HIDDEN_BODIES.get(bodyId);
        return t != null && t > 0;
    }

    /** 局终 / 重置时清空隐藏表，防止静态 map 泄漏。 */
    public static void clearHiddenBodies() {
        HIDDEN_BODIES.clear();
    }

    /**
     * 每 tick 递减；隐藏中 re-assert invisible；到期后恢复可见。
     */
    public static void tickHiddenBodies(Iterable<ServerLevel> levels) {
        if (HIDDEN_BODIES.isEmpty()) return;

        Iterator<Map.Entry<UUID, Integer>> it = HIDDEN_BODIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            UUID id = e.getKey();
            int v = e.getValue() - 1;
            if (v > 0) {
                e.setValue(v);
                // 双保险：LivingEntity 每 tick 可能清 invisible；主路径靠 PlayerBodyEntityTickMixin
                reassertBodyHidden(levels, id);
                continue;
            }
            it.remove();
            restoreBodyVisible(levels, id);
        }
    }

    /** 兼容旧无参调用（无 level 时只删 map，无法恢复实体）。 */
    public static void tickHiddenBodies() {
        // no-op for signature compatibility; HabiRoleEvents 应调用带 levels 的版本
        Iterator<Map.Entry<UUID, Integer>> it = HIDDEN_BODIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            int v = e.getValue() - 1;
            if (v <= 0) it.remove();
            else e.setValue(v);
        }
    }

    /** 隐藏窗口内 re-assert；仅走 getEntity，不扫全图。 */
    private static void reassertBodyHidden(Iterable<ServerLevel> levels, UUID id) {
        if (levels == null || id == null) return;
        for (ServerLevel level : levels) {
            Entity entity = level.getEntity(id);
            if (entity instanceof PlayerBodyEntity body) {
                body.setInvisible(true);
                return;
            }
        }
    }

    private static void restoreBodyVisible(Iterable<ServerLevel> levels, UUID id) {
        PlayerBodyEntity body = findBody(levels, id);
        if (body == null) {
            HabiTrainCore.LOGGER.info("[MimeKiller] restore skipped, body missing {}", id);
            return;
        }
        body.setInvisible(false);
        HabiTrainCore.LOGGER.info("[MimeKiller] restore body visible {}", id);
    }

    private static PlayerBodyEntity findBody(Iterable<ServerLevel> levels, UUID id) {
        if (levels == null || id == null) return null;
        for (ServerLevel level : levels) {
            Entity entity = level.getEntity(id);
            if (entity instanceof PlayerBodyEntity body) {
                return body;
            }
        }
        // 禁止全图 AABB 扫描（旧 1e7 回退会 TPS 尖刺）；实体表 miss 则放弃 restore。
        return null;
    }

    public static boolean useMime(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!HabiRoles.isHabiRole(self, HabiRoles.MIME_KILLER)) return false;

        ServerPlayer target = resolveTarget(self, ctx.target());
        if (target == null || target.getUUID().equals(self.getUUID())) return false;

        int ticks = MIME_DURATION_SECONDS * 20;
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 255, false, false, true));
        target.addEffect(new MobEffectInstance(MobEffects.JUMP, ticks, 128, false, false, true));
        try {
            target.addEffect(new MobEffectInstance(ModEffects.VOICE_SILENCE, ticks, 0, false, false, false));
            target.addEffect(new MobEffectInstance(ModEffects.CHAT_BAN, ticks, 0, false, false, false));
        } catch (Throwable ignored) {}

        try {
            KEY.maybeGet(self).ifPresent(comp -> comp.rootedTicks.put(target.getUUID(), ticks));
        } catch (Throwable ignored) {}
        return true;
    }

    private static ServerPlayer resolveTarget(ServerPlayer self, UUID targetId) {
        if (targetId != null) {
            Player p = self.level().getPlayerByUUID(targetId);
            if (p instanceof ServerPlayer sp) return sp;
        }
        Vec3 eye = self.getEyePosition();
        Vec3 look = self.getLookAngle();
        double range = 8.0;
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

    @Override
    public void serverTick() {
        if (player.level().isClientSide) return;
        if (rootedTicks.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = rootedTicks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            Player t = player.level().getPlayerByUUID(e.getKey());
            if (t != null && left > 0) {
                t.setDeltaMovement(0, Math.min(0, t.getDeltaMovement().y), 0);
                t.hurtMarked = true;
                t.teleportTo(t.getX(), t.getY(), t.getZ());
                e.setValue(left);
            } else {
                it.remove();
            }
        }
    }

    @Override
    public void init() {
        psychoDiscount = 0;
        plotThinkBuys = 0;
        rootedTicks.clear();
        clearPsychoDynamicPrice();
        sync();
    }

    @Override
    public void clear() {
        init();
    }

    public void sync() {
        KEY.sync(player);
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putInt("PsychoDiscount", psychoDiscount);
        tag.putInt("PlotThinkBuys", plotThinkBuys);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        psychoDiscount = tag.getInt("PsychoDiscount");
        plotThinkBuys = tag.getInt("PlotThinkBuys");
    }

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        writeToSyncNbt(tag, registryLookup);
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        readFromSyncNbt(tag, registryLookup);
    }
}
