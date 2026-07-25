package com.habitrain.core.game.sre.role.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.HabiRoles;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREAbilityPlayerComponent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.content.entity.ServerSmokeAreaManager;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;

/**
 * 捷风：冲刺 / 烟雾 / 飞刀击杀计数。
 */
public final class SwiftWindComponent implements RoleComponent {
    public static final ComponentKey<SwiftWindComponent> KEY = ComponentRegistry.getOrCreate(
            HabiTrainCore.id("swift_wind"), SwiftWindComponent.class);

    public static final int DASH_BLOCKS = 6;
    public static final int THROWING_KNIFE_CD_SECONDS = 60;
    public static final int STARTING_BALANCE = 100;
    /** 对齐 SmokeGrenadeEntity 烟雾参数。 */
    public static final double SMOKE_RADIUS = 4.0;
    public static final int SMOKE_DURATION_TICKS = 200;

    private final Player player;
    private int killCount;
    private int knifeKills;

    public SwiftWindComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public int getKillCount() {
        return killCount;
    }

    public void onAnyKill() {
        killCount++;
        sync();
    }

    public void onThrowingKnifeKill(ServerPlayer self) {
        knifeKills++;
        // 立刻获得飞刀
        ItemStack knife = HabiRoleItems.lookupItem(HabiRoleItems.THROWING_KNIFE_ID, 1);
        if (!knife.isEmpty()) {
            if (!self.getInventory().add(knife.copy())) {
                self.drop(knife.copy(), false);
            }
            Item item = knife.getItem();
            self.getCooldowns().addCooldown(item, THROWING_KNIFE_CD_SECONDS * 20);
        }
        // 累计 2 杀刷新逐风
        if (knifeKills >= 2 || killCount >= 2) {
            refreshDashCooldown(self);
        }
        sync();
    }

    private void refreshDashCooldown(ServerPlayer self) {
        try {
            ResourceLocation dashId = HabiTrainCore.id("swift_wind_dash");
            SREAbilityPlayerComponent ability = SREAbilityPlayerComponent.KEY.get(self);
            if (ability != null) {
                ability.setSkillCooldown(dashId, 0);
            }
        } catch (Throwable ignored) {}
    }

    public static boolean useDash(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!HabiRoles.isHabiRole(self, HabiRoles.SWIFT_WIND)) return false;

        Vec3 look = self.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0, look.z);
        if (horizontal.lengthSqr() < 1.0E-4) {
            horizontal = new Vec3(self.getViewYRot(1.0f) == 0 ? 0 : -Math.sin(Math.toRadians(self.getYRot())),
                    0, Math.cos(Math.toRadians(self.getYRot())));
        }
        horizontal = horizontal.normalize();

        Vec3 start = self.position();
        Vec3 end = start.add(horizontal.scale(DASH_BLOCKS));
        // 碰撞：步进
        double step = 0.25;
        int steps = (int) Math.ceil(DASH_BLOCKS / step);
        Vec3 pos = start;
        for (int i = 0; i < steps; i++) {
            Vec3 next = pos.add(horizontal.scale(step));
            BlockHitResult hit = self.level().clip(new ClipContext(
                    pos.add(0, 0.1, 0),
                    next.add(0, 0.1, 0),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    self));
            if (hit.getType() != HitResult.Type.MISS) {
                break;
            }
            pos = next;
        }
        self.teleportTo(pos.x, pos.y, pos.z);
        self.hurtMarked = true;
        self.fallDistance = 0;
        return true;
    }

    public static boolean useSmoke(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!HabiRoles.isHabiRole(self, HabiRoles.SWIFT_WIND)) return false;
        if (!(self.level() instanceof ServerLevel level)) return false;

        // 直接在脚下创建烟雾区域（对齐 SmokeGrenadeEntity：半径 4、持续 200 tick）
        try {
            ServerSmokeAreaManager.createSmokeArea(level, self.position(), SMOKE_RADIUS, SMOKE_DURATION_TICKS);
            level.playSound(
                    null,
                    self.blockPosition(),
                    SoundEvents.FIREWORK_ROCKET_BLAST,
                    SoundSource.PLAYERS,
                    1.5f,
                    0.5f
            );
            return true;
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[SwiftWind] createSmokeArea failed, fallback to local blindness", t);
        }

        // 最后手段：仅本地黑暗/范围失明，绝不发放烟雾弹物品
        self.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, false, true));
        for (Player p : level.getEntitiesOfClass(Player.class, self.getBoundingBox().inflate(SMOKE_RADIUS))) {
            if (p != self) {
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false, true));
            }
        }
        return true;
    }

    @Override
    public void init() {
        killCount = 0;
        knifeKills = 0;
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
        tag.putInt("KillCount", killCount);
        tag.putInt("KnifeKills", knifeKills);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        killCount = tag.getInt("KillCount");
        knifeKills = tag.getInt("KnifeKills");
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
