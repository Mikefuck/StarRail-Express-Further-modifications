package com.habitrain.core.game.sre.role.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.ServerAimTargeting;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREArmorPlayerComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 卖花女：赠送花束、静止落叶计时、死亡回收赠花护盾。
 */
public final class FlowerGirlComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<FlowerGirlComponent> KEY = ComponentRegistry.getOrCreate(
            HabiTrainCore.id("flower_girl"), FlowerGirlComponent.class);

    public static final int STILL_SECONDS = 5;
    public static final int GLOW_SECONDS = 60;
    public static final int GOLD_REWARD = 50;
    /** 赠送花束技能冷却（成功赠送 / 目标已有花束 共用）。 */
    public static final int GIFT_CD_SECONDS = 30;
    public static final int MELEE_IMMUNE_SECONDS = 5;
    public static final int PEPPER_SPRAY_CD_SECONDS = 30;

    private final Player player;

    /** 目标 UUID → 已静止 tick 计数（仅当此卖花女赠送后追踪） */
    private final Map<UUID, Integer> stillTicks = new HashMap<>();
    private final Map<UUID, Vec3> lastPos = new HashMap<>();
    private final Map<UUID, Boolean> rewarded = new HashMap<>();
    private final Map<UUID, Long> rewardedGlowUntil = new HashMap<>();
    /**
     * 目标 UUID → 各层未消耗馨香护盾的"垫底层数"（赠花前目标已有的护盾层数）。
     * 护盾按栈处理：后加的层先被消耗，目标护盾数跌到 ≤ 垫底层数即视为该层馨香护盾已消耗。
     * 护盾可能经由击杀抵挡、狙击、限时盾到期等多条上游路径被扣减，故每 tick 观察层数而非拦截扣减调用。
     */
    private final Map<UUID, List<Integer>> shieldFloors = new HashMap<>();

    /** 全服近战免疫截止 gameTime（按玩家 UUID 存于该组件所属卖花女不合适；用静态弱表） */
    private static final Map<UUID, Long> MELEE_IMMUNE_UNTIL = new ConcurrentHashMap<>();

    public FlowerGirlComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public static void setMeleeImmune(Player target, long untilGameTime) {
        if (target != null) {
            MELEE_IMMUNE_UNTIL.put(target.getUUID(), untilGameTime);
        }
    }

    public static boolean isMeleeImmune(Player target) {
        if (target == null || target.level() == null) return false;
        Long until = MELEE_IMMUNE_UNTIL.get(target.getUUID());
        if (until == null) return false;
        if (target.level().getGameTime() >= until) {
            MELEE_IMMUNE_UNTIL.remove(target.getUUID());
            return false;
        }
        return true;
    }

    public static void clearMeleeImmune() {
        MELEE_IMMUNE_UNTIL.clear();
    }

    public static boolean useGift(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!HabiRoles.isHabiRole(self, HabiRoles.FLOWER_GIRL)) return false;

        ServerPlayer target = resolveTarget(self, ctx.target());
        if (target == null || target.getUUID().equals(self.getUUID())) {
            return false;
        }
        if (!HabiRoleItems.consumeBouquet(self)) {
            return false;
        }

        FlowerGirlComponent comp = KEY.get(self);
        if (HabiRoleItems.playerHasBouquet(target)) {
            // 已有花束：消耗花束；技能 CD 由注册 cooldownSeconds(30) 结算
            return true;
        }

        // 目标获得花束物品
        if (!target.getInventory().add(HabiRoleItems.createBouquet(1))) {
            target.drop(HabiRoleItems.createBouquet(1), false);
        }

        // 馨香护盾
        try {
            SREArmorPlayerComponent armor = SREArmorPlayerComponent.KEY.get(target);
            if (armor != null) {
                int floor = armor.getArmor();
                armor.addArmor();
                if (comp != null) {
                    comp.shieldFloors.computeIfAbsent(target.getUUID(), k -> new ArrayList<>()).add(floor);
                }
            }
        } catch (Throwable ignored) {}

        // 追踪静止
        if (comp != null) {
            comp.stillTicks.put(target.getUUID(), 0);
            comp.lastPos.put(target.getUUID(), target.position());
            comp.rewarded.put(target.getUUID(), false);
        }
        return true;
    }

    private static ServerPlayer resolveTarget(ServerPlayer self, UUID targetId) {
        return ServerAimTargeting.resolve(self, targetId, 6.0D);
    }

    @Override
    public void serverTick() {
        if (!(player instanceof ServerPlayer self) || self.level().isClientSide) return;
        if (!HabiRoles.isHabiRole(self, HabiRoles.FLOWER_GIRL)) return;

        pruneConsumedShields(self.level());

        Iterator<Map.Entry<UUID, Integer>> it = stillTicks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            UUID id = e.getKey();
            if (Boolean.TRUE.equals(rewarded.get(id))) continue;
            Player target = self.level().getPlayerByUUID(id);
            if (!(target instanceof ServerPlayer sp) || sp.isSpectator()) {
                it.remove();
                lastPos.remove(id);
                continue;
            }
            Vec3 prev = lastPos.getOrDefault(id, sp.position());
            Vec3 cur = sp.position();
            if (prev.distanceToSqr(cur) < 0.01) {
                int ticks = e.getValue() + 1;
                e.setValue(ticks);
                if (ticks >= STILL_SECONDS * 20) {
                    onStillReward(self, sp);
                    rewarded.put(id, true);
                }
            } else {
                e.setValue(0);
            }
            lastPos.put(id, cur);
        }
    }

    private void onStillReward(ServerPlayer flowerGirl, ServerPlayer target) {
        // 落叶
        tryPlaceFallenLeaves(target);
        // 金币
        try {
            SREPlayerShopComponent shop = SREPlayerShopComponent.KEY.get(flowerGirl);
            if (shop != null) shop.addToBalance(GOLD_REWARD);
        } catch (Throwable ignored) {}
        // 透视轮廓 60s
        int durationTicks = GLOW_SECONDS * 20;
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, durationTicks, 0, false, false, true));
        rewardedGlowUntil.put(target.getUUID(), target.level().getGameTime() + durationTicks);
    }

    private static void tryPlaceFallenLeaves(ServerPlayer target) {
        if (!(target.level() instanceof ServerLevel level)) return;
        Block block = BuiltInRegistries.BLOCK.get(HabiRoleItems.FALLEN_LEAVES_ID);
        if (block == null || block == Blocks.AIR) return;
        BlockPos feet = target.blockPosition();
        BlockPos above = feet.above();
        // 覆盖脚下方块：优先放在 feet，若 feet 已被非空气半砖/地毯占用则尝试 feet.above()
        if (level.getBlockState(feet).canBeReplaced()) {
            level.setBlock(feet, block.defaultBlockState(), 3);
        } else if (level.getBlockState(above).canBeReplaced()) {
            level.setBlock(above, block.defaultBlockState(), 3);
        }
    }

    /** 卖花女死亡：仅回收其赠花对象的花束、馨香护盾与发光。 */
    public static void revokeGifts(ServerPlayer flowerGirl) {
        if (flowerGirl == null || !(flowerGirl.level() instanceof ServerLevel level)) return;
        FlowerGirlComponent comp;
        try {
            comp = KEY.get(flowerGirl);
        } catch (Throwable ignored) {
            return;
        }
        comp.pruneConsumedShields(level);
        long now = level.getGameTime();
        for (UUID id : giftedIds(comp)) {
            if (!(level.getPlayerByUUID(id) instanceof ServerPlayer p)) continue;
            boolean otherFlowerGirlGifted = isGiftedByOtherAliveFlowerGirl(level, flowerGirl, id);
            // 移除此卖花女赠送的花束物品
            if (!otherFlowerGirlGifted) {
                for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                    if (HabiRoleItems.isBouquet(p.getInventory().getItem(i))) {
                        p.getInventory().setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                    }
                }
            } else {
                HabiRoleItems.consumeBouquet(p);
            }
            // 只收回尚未消耗的馨香护盾层，不动其他来源的护盾
            List<Integer> floors = comp.shieldFloors.get(id);
            int intact = floors == null ? 0 : floors.size();
            if (intact > 0) {
                try {
                    SREArmorPlayerComponent armor = SREArmorPlayerComponent.KEY.get(p);
                    if (armor != null) armor.removeArmor(Math.min(intact, armor.getArmor()));
                } catch (Throwable ignored) {}
            }
            Long glowUntil = comp.rewardedGlowUntil.get(id);
            if (Boolean.TRUE.equals(comp.rewarded.get(id))
                    && glowUntil != null
                    && now < glowUntil
                    && !isGlowingFromOtherAliveFlowerGirl(level, flowerGirl, id, now)) {
                MobEffectInstance activeGlow = p.getEffect(MobEffects.GLOWING);
                if (activeGlow != null && activeGlow.getDuration() <= GLOW_SECONDS * 20) {
                    p.removeEffect(MobEffects.GLOWING);
                }
            }
        }
        comp.shieldFloors.clear();
        comp.stillTicks.clear();
        comp.lastPos.clear();
        comp.rewarded.clear();
        comp.rewardedGlowUntil.clear();
        MELEE_IMMUNE_UNTIL.remove(flowerGirl.getUUID());
    }

    private static boolean isGiftedByOtherAliveFlowerGirl(ServerLevel level, ServerPlayer dying, UUID targetId) {
        for (ServerPlayer sp : level.players()) {
            if (sp == dying || !ServerAimTargeting.isAliveParticipant(sp)) continue;
            if (!HabiRoles.isHabiRole(sp, HabiRoles.FLOWER_GIRL)) continue;
            try {
                FlowerGirlComponent other = KEY.get(sp);
                if (other != null && giftedIds(other).contains(targetId)) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static boolean isGlowingFromOtherAliveFlowerGirl(ServerLevel level, ServerPlayer dying, UUID targetId, long now) {
        for (ServerPlayer sp : level.players()) {
            if (sp == dying || !ServerAimTargeting.isAliveParticipant(sp)) continue;
            if (!HabiRoles.isHabiRole(sp, HabiRoles.FLOWER_GIRL)) continue;
            try {
                FlowerGirlComponent other = KEY.get(sp);
                Long until = other != null ? other.rewardedGlowUntil.get(targetId) : null;
                if (until != null && now < until) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** 护盾数已跌到垫底层数及以下的馨香护盾层视为已消耗，移出追踪。目标离线时保持原状。 */
    private void pruneConsumedShields(net.minecraft.world.level.Level level) {
        for (Map.Entry<UUID, List<Integer>> e : shieldFloors.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (!(level.getPlayerByUUID(e.getKey()) instanceof ServerPlayer target)) continue;
            int current;
            try {
                current = SREArmorPlayerComponent.KEY.get(target).getArmor();
            } catch (Throwable ignored) {
                continue;
            }
            e.getValue().removeIf(floor -> current <= floor);
        }
    }

    /** 赠花对象：静止追踪表（仅成功赠花时写入）与护盾追踪表的并集。 */
    private static java.util.Set<UUID> giftedIds(FlowerGirlComponent comp) {
        java.util.Set<UUID> ids = new java.util.HashSet<>(comp.rewarded.keySet());
        ids.addAll(comp.shieldFloors.keySet());
        return ids;
    }

    @Override
    public void init() {
        shieldFloors.clear();
        stillTicks.clear();
        lastPos.clear();
        rewarded.clear();
        rewardedGlowUntil.clear();
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
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {}

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {}

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        ListTag still = new ListTag();
        for (Map.Entry<UUID, Integer> e : stillTicks.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            CompoundTag line = new CompoundTag();
            line.putUUID("Id", e.getKey());
            line.putInt("Ticks", e.getValue());
            still.add(line);
        }
        tag.put("StillTicks", still);
        ListTag rewardedTag = new ListTag();
        for (Map.Entry<UUID, Boolean> e : rewarded.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            CompoundTag line = new CompoundTag();
            line.putUUID("Id", e.getKey());
            line.putBoolean("Rewarded", e.getValue());
            rewardedTag.add(line);
        }
        tag.put("Rewarded", rewardedTag);
        ListTag shieldTag = new ListTag();
        for (Map.Entry<UUID, List<Integer>> e : shieldFloors.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            CompoundTag line = new CompoundTag();
            line.putUUID("Id", e.getKey());
            line.putIntArray("Floors", e.getValue());
            shieldTag.add(line);
        }
        tag.put("ShieldFloors", shieldTag);
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        shieldFloors.clear();
        stillTicks.clear();
        lastPos.clear();
        rewarded.clear();
        if (tag.contains("ShieldFloors", Tag.TAG_LIST)) {
            ListTag shieldTag = tag.getList("ShieldFloors", Tag.TAG_COMPOUND);
            for (int i = 0; i < shieldTag.size(); i++) {
                CompoundTag line = shieldTag.getCompound(i);
                if (!line.hasUUID("Id")) continue;
                List<Integer> floors = new ArrayList<>();
                for (int floor : line.getIntArray("Floors")) floors.add(floor);
                shieldFloors.put(line.getUUID("Id"), floors);
            }
        }
        if (tag.contains("StillTicks", Tag.TAG_LIST)) {
            ListTag still = tag.getList("StillTicks", Tag.TAG_COMPOUND);
            for (int i = 0; i < still.size(); i++) {
                CompoundTag line = still.getCompound(i);
                if (!line.hasUUID("Id")) continue;
                stillTicks.put(line.getUUID("Id"), line.getInt("Ticks"));
            }
        }
        if (tag.contains("Rewarded", Tag.TAG_LIST)) {
            ListTag rewardedTag = tag.getList("Rewarded", Tag.TAG_COMPOUND);
            for (int i = 0; i < rewardedTag.size(); i++) {
                CompoundTag line = rewardedTag.getCompound(i);
                if (!line.hasUUID("Id")) continue;
                rewarded.put(line.getUUID("Id"), line.getBoolean("Rewarded"));
            }
        }
    }
}
