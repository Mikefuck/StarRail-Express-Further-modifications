package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.SinRoleRules;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import com.habitrain.core.game.sre.roleoverride.SreRolePoolFilter;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerPsychoComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.agmas.noellesroles.init.RoleInitialItems;
import org.agmas.noellesroles.utils.RoleUtils;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 暴怒：安全结束立刻原版狂暴；结束反胃 5s；被好人打中打断并开第二次狂暴；第二次结束随机转非罪职业。
 * 未转职前随杀手共享胜利（角色注册 neutralForKiller）。
 */
public final class WrathComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<WrathComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_wrath"), WrathComponent.class);

    public static final int NAUSEA_TICKS = 20 * 5;

    public enum Phase {
        IDLE,
        FIRST_PSYCHO,
        NAUSEA,
        SECOND_PSYCHO,
        TRANSFORMED
    }

    private final Player player;
    private Phase phase = Phase.IDLE;
    private long nauseaUntilGameTime;
    private boolean firstPsychoStarted;
    private int transformRetryCooldown;

    public WrathComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public Phase getPhase() {
        return phase;
    }

    public int getStage() {
        // Legacy client filter used stage; keep 0 so old HUD is inert if present.
        return 0;
    }

    /** Safe-time end: start first psycho for every living Wrath. */
    public static void onSafeTimeEnd(ServerLevel level) {
        if (level == null || SevenSins.WRATH == null) return;
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || !game.isRunning()) return;

        for (ServerPlayer p : level.players()) {
            if (p == null || p.isSpectator()) continue;
            if (!game.isRole(p, SevenSins.WRATH)) continue;
            try {
                WrathComponent c = KEY.get(p);
                if (c == null || c.phase == Phase.TRANSFORMED) continue;
                if (c.firstPsychoStarted) continue;
                c.startPsychoWave(p, Phase.FIRST_PSYCHO);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Wrath] first psycho failed for {}",
                        p.getGameProfile().getName(), t);
            }
        }
    }

    public static boolean isWrathPlayer(ServerLevel level, ServerPlayer p) {
        if (level == null || p == null || SevenSins.WRATH == null) return false;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            return game != null && game.isRole(p, SevenSins.WRATH);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Good-aligned player hit this wrath: cancel lethal if still protectable, interrupt first
     * psycho and open second wave when applicable.
     *
     * @return {@code true} if the hit was handled as a wrath trigger (caller should cancel death/attack damage)
     */
    public boolean onHitByGood(ServerPlayer self, ServerPlayer attacker) {
        if (self == null || attacker == null) return false;
        if (phase == Phase.TRANSFORMED || phase == Phase.SECOND_PSYCHO) return false;
        if (!(self.level() instanceof ServerLevel level)) return false;
        if (!SinRoleRules.isGoodAligned(level, attacker)) return false;

        if (phase == Phase.FIRST_PSYCHO) {
            self.setHealth(self.getMaxHealth());
            stopPsycho(self);
            startPsychoWave(self, Phase.SECOND_PSYCHO);
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_wrath.second_trigger"),
                    true
            );
            attacker.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_wrath.attacker_trigger"),
                    true
            );
            return true;
        }

        if (phase == Phase.NAUSEA || phase == Phase.IDLE) {
            // Only re-trigger second wave if first already ran.
            if (!firstPsychoStarted) return false;
            self.setHealth(self.getMaxHealth());
            self.removeEffect(MobEffects.CONFUSION);
            nauseaUntilGameTime = 0;
            startPsychoWave(self, Phase.SECOND_PSYCHO);
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_wrath.second_trigger"),
                    true
            );
            return true;
        }
        return false;
    }

    private void startPsychoWave(ServerPlayer self, Phase next) {
        if (self == null) return;
        stopPsycho(self);
        boolean started;
        try {
            started = SREPlayerShopComponent.usePsychoMode(self);
            if (!started) {
                started = SREPlayerShopComponent.usePsychoMode_time(self, 20 * 30, 1);
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Wrath] usePsychoMode failed", t);
            started = false;
        }
        phase = next;
        if (next == Phase.FIRST_PSYCHO) {
            firstPsychoStarted = true;
        }
        KEY.sync(self);
        self.displayClientMessage(
                Component.translatable(
                        next == Phase.FIRST_PSYCHO
                                ? "message.habitrain_core.sin_wrath.first_psycho"
                                : "message.habitrain_core.sin_wrath.second_psycho"
                ),
                true
        );
        HabiTrainCore.LOGGER.info("[Wrath] {} start {} psycho started={}",
                self.getGameProfile().getName(), next, started);
    }

    private static void stopPsycho(ServerPlayer self) {
        try {
            SREPlayerPsychoComponent ppc = SREPlayerPsychoComponent.KEY.get(self);
            if (ppc != null && ppc.getPsychoTicks() > 0) {
                ppc.stopPsychoAndSync();
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.debug("[Wrath] stopPsycho failed", t);
        }
    }

    private static boolean isPsychoActive(ServerPlayer self) {
        try {
            SREPlayerPsychoComponent ppc = SREPlayerPsychoComponent.KEY.get(self);
            return ppc != null && ppc.getPsychoTicks() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void transformToRandomNonSin(ServerPlayer self) {
        if (self == null || !(self.level() instanceof ServerLevel level)) return;
        if (phase == Phase.TRANSFORMED) return;

        List<SRERole> pool = buildNonSinPool();
        if (pool.isEmpty()) {
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_wrath.transform_fail"),
                    true
            );
            transformRetryCooldown = 40;
            HabiTrainCore.LOGGER.warn("[Wrath] empty non-sin pool for {}",
                    self.getGameProfile().getName());
            return;
        }

        SRERole next = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        try {
            clearInventory(self);
            // 统一转职入口（替代原 RoleUtils.changeRole + reassignRole 双调用，消除双重 ModdedRoleAssigned）：
            // record=false（不写默认时间线）、addStats=true；阵营由 resolveFactionFromSreRole 推导
            BlackoutRoleManager.reassignRole(level, self.getUUID(), next,
                    BlackoutRoleManager.resolveFactionFromSreRole(next), false, true);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("[Wrath] changeRole failed for {}",
                    self.getGameProfile().getName(), t);
            transformRetryCooldown = 40;
            return;
        }

        clearInventory(self);
        try {
            RoleInitialItems.addInitialItemsForRole(self, next);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Wrath] addInitialItems failed", t);
        }
        try {
            RoleUtils.sendWelcomeAnnouncement(self);
        } catch (Throwable ignored) {
        }

        phase = Phase.TRANSFORMED;
        KEY.sync(self);
        String name;
        try {
            name = next.getName().getString();
        } catch (Throwable t) {
            name = next.getIdentifier().toString();
        }
        self.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_wrath.transformed", name),
                true
        );
        HabiTrainCore.LOGGER.info("[Wrath] {} transformed -> {}",
                self.getGameProfile().getName(), next.getIdentifier());
    }

    private static List<SRERole> buildNonSinPool() {
        Set<SRERole> occupationCompanions = new HashSet<>();
        // 池来自 v2 目录（v1 回退），使 v2 ADD 角色能进入愤怒转职池（audit P2-1）。
        List<SRERole> visibleRoles =
                com.habitrain.core.role.catalog.RoleCatalogConsumer.visiblePool();
        for (SRERole role : visibleRoles) {
            if (role == null) continue;
            try {
                List<SRERole> companions = role.getoccupationRoles();
                if (companions != null && !companions.isEmpty()) {
                    occupationCompanions.addAll(companions);
                }
            } catch (Throwable ignored) {
            }
        }

        List<SRERole> pool = new ArrayList<>();
        for (SRERole role : visibleRoles) {
            if (role == null) continue;
            if (SevenSins.isSin(role)) continue;
            if (!SreRolePoolFilter.isCurrentModeRandomizable(role)) continue;
            try {
                List<SRERole> own = role.getoccupationRoles();
                if (own != null && !own.isEmpty()) continue;
            } catch (Throwable ignored) {
            }
            if (occupationCompanions.contains(role)) continue;
            try {
                if (role.getOccupiedRoleCount() > 1) continue;
            } catch (Throwable ignored) {
            }
            pool.add(role);
        }
        SreRolePoolFilter.warnIfLeaky("WrathTransform", pool);
        return pool;
    }

    private static void clearInventory(ServerPlayer player) {
        if (player == null) return;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            player.getInventory().setItem(i, ItemStack.EMPTY);
        }
        player.getInventory().setChanged();
    }

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        phase = Phase.IDLE;
        nauseaUntilGameTime = 0;
        firstPsychoStarted = false;
        transformRetryCooldown = 0;
    }

    @Override
    public void serverTick() {
        if (player.level().isClientSide) return;
        if (!(player instanceof ServerPlayer self)) return;
        if (!(self.level() instanceof ServerLevel level)) return;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        boolean isWrath = game != null && SevenSins.WRATH != null && game.isRole(self, SevenSins.WRATH);
        if (!isWrath || self.isSpectator()) {
            return;
        }
        if (phase == Phase.TRANSFORMED) return;

        long now = level.getGameTime();

        if (phase == Phase.FIRST_PSYCHO && !isPsychoActive(self)) {
            phase = Phase.NAUSEA;
            nauseaUntilGameTime = now + NAUSEA_TICKS;
            self.addEffect(new MobEffectInstance(MobEffects.CONFUSION, NAUSEA_TICKS, 0, false, true, true));
            KEY.sync(self);
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_wrath.nausea"),
                    true
            );
            return;
        }

        if (phase == Phase.NAUSEA && now >= nauseaUntilGameTime) {
            phase = Phase.IDLE;
            KEY.sync(self);
            return;
        }

        if (phase == Phase.SECOND_PSYCHO) {
            if (transformRetryCooldown > 0) {
                transformRetryCooldown--;
            }
            if (!isPsychoActive(self) && transformRetryCooldown <= 0) {
                transformToRandomNonSin(self);
            }
        }
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putString("Phase", phase.name());
        tag.putLong("NauseaUntil", nauseaUntilGameTime);
        tag.putBoolean("FirstStarted", firstPsychoStarted);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        try {
            phase = Phase.valueOf(tag.getString("Phase"));
        } catch (Throwable t) {
            phase = Phase.IDLE;
        }
        nauseaUntilGameTime = tag.getLong("NauseaUntil");
        firstPsychoStarted = tag.getBoolean("FirstStarted");
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