package com.habitrain.core.game.sre.role.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.HabiRoles;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREArmorPlayerComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 卖花女：赠送花束、静止落叶计时、死亡清场。
 */
public final class FlowerGirlComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<FlowerGirlComponent> KEY = ComponentRegistry.getOrCreate(
            HabiTrainCore.id("flower_girl"), FlowerGirlComponent.class);

    public static final int STILL_SECONDS = 5;
    public static final int GLOW_SECONDS = 60;
    public static final int GOLD_REWARD = 50;
    /** 赠送花束技能冷却（成功赠送 / 目标已有花束 共用）。 */
    public static final int GIFT_CD_SECONDS = 30;
    public static final int MELEE_IMMUNE_SECONDS = 10;
    public static final int PEPPER_SPRAY_CD_SECONDS = 30;

    private final Player player;

    /** 目标 UUID → 已静止 tick 计数（仅当此卖花女赠送后追踪） */
    private final Map<UUID, Integer> stillTicks = new HashMap<>();
    private final Map<UUID, Vec3> lastPos = new HashMap<>();
    private final Map<UUID, Boolean> rewarded = new HashMap<>();

    /** 全服近战免疫截止 gameTime（按玩家 UUID 存于该组件所属卖花女不合适；用静态弱表） */
    private static final Map<UUID, Long> MELEE_IMMUNE_UNTIL = new HashMap<>();

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
                armor.addArmor();
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
        if (targetId != null) {
            Player p = self.level().getPlayerByUUID(targetId);
            if (p instanceof ServerPlayer sp) return sp;
        }
        // 射线瞄准最近玩家
        Vec3 eye = self.getEyePosition();
        Vec3 look = self.getLookAngle();
        double range = 6.0;
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
        if (!(player instanceof ServerPlayer self) || self.level().isClientSide) return;
        if (!HabiRoles.isHabiRole(self, HabiRoles.FLOWER_GIRL)) return;

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
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_SECONDS * 20, 0, false, false, true));
    }

    private static void tryPlaceFallenLeaves(ServerPlayer target) {
        if (!(target.level() instanceof ServerLevel level)) return;
        Block block = BuiltInRegistries.BLOCK.get(HabiRoleItems.FALLEN_LEAVES_ID);
        if (block == null || block == Blocks.AIR) return;
        BlockPos feet = target.blockPosition();
        BlockPos place = feet.below().above(); // 脚下方块上覆盖：优先脚下空气替换为落叶
        // 覆盖脚下方块：若脚下是固体上表面，放在 feet
        if (level.getBlockState(feet).canBeReplaced()) {
            level.setBlock(feet, block.defaultBlockState(), 3);
        } else if (level.getBlockState(place).canBeReplaced()) {
            level.setBlock(place, block.defaultBlockState(), 3);
        }
    }

    /** 卖花女死亡：清全场花束标记、护盾、发光。 */
    public static void clearAllBouquets(ServerLevel level) {
        if (level == null) return;
        for (ServerPlayer p : level.players()) {
            // 移除花束物品
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                if (HabiRoleItems.isBouquet(p.getInventory().getItem(i))) {
                    p.getInventory().setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                }
            }
            // 清护盾
            try {
                SREArmorPlayerComponent armor = SREArmorPlayerComponent.KEY.get(p);
                if (armor != null && armor.armor > 0) {
                    armor.armor = 0;
                }
            } catch (Throwable ignored) {}
            p.removeEffect(MobEffects.GLOWING);
            try {
                FlowerGirlComponent c = KEY.get(p);
                c.stillTicks.clear();
                c.lastPos.clear();
                c.rewarded.clear();
            } catch (Throwable ignored) {}
        }
        MELEE_IMMUNE_UNTIL.clear();
    }

    @Override
    public void init() {
        stillTicks.clear();
        lastPos.clear();
        rewarded.clear();
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
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {}

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {}
}
