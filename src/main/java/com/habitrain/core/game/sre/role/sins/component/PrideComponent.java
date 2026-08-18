package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

/**
 * 傲慢：人群光环武器免疫、击杀破防。商店固定见 {@code SevenSinShops.prideShop()}。
 */
public final class PrideComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<PrideComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_pride"), PrideComponent.class);

    public static final double AURA_RANGE = 8.0;
    public static final int AURA_OTHERS_NEEDED = 3;
    public static final int BREAK_IMMUNE_SECONDS = 60;

    private final Player player;
    private long breakImmuneUntilGameTime;
    private boolean weaponImmune;
    /** 上次光环重算的游戏刻（review L3 的 5 tick 节流）。 */
    private long lastAuraCheckTick = Long.MIN_VALUE;

    public PrideComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public long getBreakImmuneUntilGameTime() {
        return breakImmuneUntilGameTime;
    }

    public boolean isWeaponImmune() {
        return weaponImmune;
    }

    public void setBreakImmuneUntil(long gameTimeExclusive) {
        this.breakImmuneUntilGameTime = gameTimeExclusive;
        KEY.sync(player);
    }

    public void onPrideKill(ServerLevel level) {
        if (level == null) return;
        // Clear weaponImmune before sync so clients see both break window and lost aura in one packet.
        weaponImmune = false;
        setBreakImmuneUntil(level.getGameTime() + BREAK_IMMUNE_SECONDS * 20L);
    }

    public static boolean isPrideWeaponImmune(Player target) {
        if (target == null) return false;
        try {
            PrideComponent c = KEY.get(target);
            return c != null && c.weaponImmune;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        breakImmuneUntilGameTime = 0;
        weaponImmune = false;
    }

    @Override
    public void serverTick() {
        if (player.level().isClientSide) return;
        if (!(player instanceof ServerPlayer self)) return;
        if (!(self.level() instanceof ServerLevel level)) return;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        boolean isPride = game != null && SevenSins.PRIDE != null && game.isRole(self, SevenSins.PRIDE);
        if (!isPride || self.isSpectator()) {
            if (weaponImmune) {
                weaponImmune = false;
            }
            return;
        }

        // 每 5 tick 重算一次光环（距离扫描是 O(维度玩家数)），期间沿用上次结果
        // （review L3）。40 tick 的发光缓冲天然容忍 5 tick 的判定粒度。
        long now = level.getGameTime();
        if (now - lastAuraCheckTick < 5) {
            return;
        }
        lastAuraCheckTick = now;

        int others = countOtherAliveNearby(self, level, game);
        boolean aura = others >= AURA_OTHERS_NEEDED;
        boolean broken = now < breakImmuneUntilGameTime;
        weaponImmune = aura && !broken;

        if (aura) {
            // Refresh short glowing so it does not linger after leaving the crowd.
            self.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false, true));
        }
    }

    private static int countOtherAliveNearby(ServerPlayer self, ServerLevel level, SREGameWorldComponent game) {
        double rangeSq = AURA_RANGE * AURA_RANGE;
        int count = 0;
        // 用 hasAnyAlive 替代 getAllAlive 列表拷贝（review L3）。
        boolean blackout = BlackoutRoleManager.hasAnyAlive(level);
        for (ServerPlayer other : level.players()) {
            if (other == self || other.isSpectator()) continue;
            if (self.distanceToSqr(other) > rangeSq) continue;
            if (!isAliveParticipant(level, game, other, blackout)) continue;
            count++;
        }
        return count;
    }

    private static boolean isAliveParticipant(ServerLevel level, SREGameWorldComponent game,
                                             ServerPlayer p, boolean blackout) {
        if (p == null || p.isSpectator()) return false;
        if (blackout) {
            return BlackoutRoleManager.isAlive(level, p.getUUID());
        }
        if (game == null || !game.isRunning()) return false;
        return game.getRole(p) != null;
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putLong("BreakImmuneUntil", breakImmuneUntilGameTime);
        tag.putBoolean("WeaponImmune", weaponImmune);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        breakImmuneUntilGameTime = tag.getLong("BreakImmuneUntil");
        weaponImmune = tag.getBoolean("WeaponImmune");
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
