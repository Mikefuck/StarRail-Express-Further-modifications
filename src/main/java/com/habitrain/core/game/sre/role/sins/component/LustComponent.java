package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;
import pro.fazeclan.river.stupid_express.constants.SEModifiers;
import pro.fazeclan.river.stupid_express.modifier.lovers.cca.LoversComponent;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 色欲：一阶段观察蓄能（只读真正恋人）；二阶段一次欲望标记；不添加 LOVERS。
 */
public final class LustComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<LustComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_lust"), LustComponent.class);

    public static final int PHASE1_CHARGE_TICKS = 600; // 30s
    public static final double OBSERVE_RANGE = 8.0;

    private final Player player;

    /** Phase 1 observe skill toggle (held/active). */
    private boolean observing;
    /** Accumulated observe charge; pauses without reset when conditions break. */
    private int chargeTicks;
    /** True after charge completes (phase 2 unlocked). */
    private boolean phase2;
    /** Once-per-game desire mark used. */
    private boolean desireMarkUsed;
    /** Desire-marked player UUIDs (visual / identity only — not LOVERS). */
    private final Set<UUID> desireMarked = new HashSet<>();
    /** Cached true-lover UUIDs for client highlight (read-only). */
    private final Set<UUID> knownLovers = new HashSet<>();
    /** 真爱对扫描缓存（10 tick 一次，review L4）。 */
    private transient List<ServerPlayer> cachedPair;
    private long lastPairScanTick;

    public LustComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public boolean isObserving() {
        return observing;
    }

    public int getChargeTicks() {
        return chargeTicks;
    }

    public boolean isPhase2() {
        return phase2;
    }

    public boolean isDesireMarkUsed() {
        return desireMarkUsed;
    }

    public Set<UUID> getDesireMarked() {
        return Collections.unmodifiableSet(desireMarked);
    }

    public Set<UUID> getKnownLovers() {
        return Collections.unmodifiableSet(knownLovers);
    }

    public boolean isDesireMarked(UUID id) {
        return id != null && desireMarked.contains(id);
    }

    public boolean isKnownLover(UUID id) {
        return id != null && knownLovers.contains(id);
    }

    /**
     * Phase 1 skill: toggle observe mode. Charging is handled in {@link #serverTick()}.
     */
    public static boolean useObserve(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!(self.level() instanceof ServerLevel level)) return false;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || SevenSins.LUST == null || !game.isRole(self, SevenSins.LUST)) {
            return false;
        }

        LustComponent c = KEY.get(self);
        if (c == null) return false;

        if (c.phase2) {
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_lust.phase2_ready"), true);
            return false;
        }

        c.observing = !c.observing;
        KEY.sync(self);
        self.displayClientMessage(
                Component.translatable(
                        c.observing
                                ? "message.habitrain_core.sin_lust.observe_on"
                                : "message.habitrain_core.sin_lust.observe_off",
                        c.chargeTicks * 100 / PHASE1_CHARGE_TICKS
                ),
                true
        );
        return true;
    }

    /**
     * Phase 2 skill once: mark every other alive participant with desire (not LOVERS).
     */
    public static boolean useDesireMark(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!(self.level() instanceof ServerLevel level)) return false;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || SevenSins.LUST == null || !game.isRole(self, SevenSins.LUST)) {
            return false;
        }

        LustComponent c = KEY.get(self);
        if (c == null) return false;

        if (!c.phase2) {
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_lust.need_phase2"), true);
            return false;
        }
        if (c.desireMarkUsed) {
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_lust.mark_used"), true);
            return false;
        }

        c.desireMarkUsed = true;
        c.observing = false;
        c.desireMarked.clear();
        int count = 0;
        for (ServerPlayer other : collectAlivePlayers(level)) {
            if (other == null || other.getUUID().equals(self.getUUID())) continue;
            c.desireMarked.add(other.getUUID());
            count++;
            other.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_lust.marked_target"), true);
        }
        KEY.sync(self);
        self.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_lust.mark_done", count), true);
        HabiTrainCore.LOGGER.info("[Lust] {} desire-marked {} players",
                self.getGameProfile().getName(), count);
        return true;
    }

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        observing = false;
        chargeTicks = 0;
        phase2 = false;
        desireMarkUsed = false;
        desireMarked.clear();
        knownLovers.clear();
        cachedPair = null;
        lastPairScanTick = 0;
    }

    @Override
    public void serverTick() {
        if (player.level().isClientSide) return;
        if (!(player instanceof ServerPlayer self)) return;
        if (!(self.level() instanceof ServerLevel level)) return;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        boolean isLust = game != null && SevenSins.LUST != null && game.isRole(self, SevenSins.LUST);
        if (!isLust || self.isSpectator()) {
            if (observing) observing = false;
            return;
        }

        // 已知真爱名单仅用于 phase-1 高亮（只读），10 tick 刷新一次足够；
        // 每 tick 全员组件查询无必要（review L4）。
        if (level.getGameTime() % 10L == 0L) {
            refreshKnownLovers(level);
        }

        if (phase2) {
            if (observing) {
                observing = false;
                cachedPair = null;
                KEY.sync(self);
            }
            return;
        }

        if (!observing) return;

        // 真爱对扫描是 O(存活玩家²)，同样 10 tick 一次；LOS 与充能仍逐 tick
        // （review L4）。缓存的玩家引用每 tick 校验存活/未移除。
        long now = level.getGameTime();
        if (cachedPair == null || now - lastPairScanTick >= 10) {
            lastPairScanTick = now;
            cachedPair = findTrueLoverPair(level);
        }
        List<ServerPlayer> pair = cachedPair;
        boolean charging = false;
        if (pair != null && pair.size() == 2
                && pair.get(0).isAlive() && !pair.get(0).isRemoved()
                && pair.get(1).isAlive() && !pair.get(1).isRemoved()) {
            ServerPlayer a = pair.get(0);
            ServerPlayer b = pair.get(1);
            if (isNearWithLos(self, a) && isNearWithLos(self, b)) {
                charging = true;
            }
        }

        if (charging) {
            chargeTicks = Math.min(PHASE1_CHARGE_TICKS, chargeTicks + 1);
            if (chargeTicks >= PHASE1_CHARGE_TICKS) {
                phase2 = true;
                observing = false;
                cachedPair = null;
                KEY.sync(self);
                self.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_lust.phase2_unlock"),
                        true
                );
                HabiTrainCore.LOGGER.info("[Lust] {} entered phase 2",
                        self.getGameProfile().getName());
                return;
            }
            if (level.getGameTime() % 40L == 0L) {
                int pct = chargeTicks * 100 / PHASE1_CHARGE_TICKS;
                self.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_lust.charging", pct),
                        true
                );
            }
            if (chargeTicks % 20 == 0) {
                KEY.sync(self);
            }
        } else if (level.getGameTime() % 40L == 0L) {
            int pct = chargeTicks * 100 / PHASE1_CHARGE_TICKS;
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_lust.paused", pct),
                    true
            );
        }
    }

    private void refreshKnownLovers(ServerLevel level) {
        Set<UUID> next = new HashSet<>();
        for (ServerPlayer p : collectAlivePlayers(level)) {
            if (p == null) continue;
            if (isTrueLover(level, p)) {
                next.add(p.getUUID());
            }
        }
        if (!next.equals(knownLovers)) {
            knownLovers.clear();
            knownLovers.addAll(next);
            KEY.sync(player);
        }
    }

    /**
     * Find a mutual true-lover pair (A.lover==B and B.lover==A), both alive.
     */
    public static List<ServerPlayer> findTrueLoverPair(ServerLevel level) {
        if (level == null) return null;
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        List<ServerPlayer> lovers = new java.util.ArrayList<>();
        for (ServerPlayer p : collectAlivePlayers(level)) {
            if (game != null && SevenSins.LUST != null && game.isRole(p, SevenSins.LUST)) {
                continue;
            }
            if (isTrueLover(level, p)) {
                lovers.add(p);
            }
        }
        if (lovers.size() < 2) return null;

        for (int i = 0; i < lovers.size(); i++) {
            ServerPlayer a = lovers.get(i);
            LoversComponent ca = safeLovers(a);
            if (ca == null || ca.getLover() == null) continue;
            for (int j = i + 1; j < lovers.size(); j++) {
                ServerPlayer b = lovers.get(j);
                if (!ca.getLover().equals(b.getUUID())) continue;
                LoversComponent cb = safeLovers(b);
                if (cb != null && a.getUUID().equals(cb.getLover())) {
                    return List.of(a, b);
                }
            }
        }
        return null;
    }

    public static boolean isTrueLover(ServerLevel level, ServerPlayer p) {
        if (level == null || p == null || p.isSpectator()) return false;
        try {
            WorldModifierComponent wmc = WorldModifierComponent.KEY.get(level);
            if (wmc == null || SEModifiers.LOVERS == null) return false;
            if (!wmc.isModifier(p, SEModifiers.LOVERS)) return false;
            LoversComponent lc = LoversComponent.KEY.get(p);
            return lc != null && lc.isLover();
        } catch (Throwable t) {
            return false;
        }
    }

    private static LoversComponent safeLovers(ServerPlayer p) {
        try {
            return LoversComponent.KEY.get(p);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isNearWithLos(ServerPlayer lust, ServerPlayer target) {
        if (lust == null || target == null) return false;
        if (lust.distanceToSqr(target) > OBSERVE_RANGE * OBSERVE_RANGE) return false;
        return hasLineOfSight(lust, target);
    }

    public static boolean hasLineOfSight(ServerPlayer from, ServerPlayer to) {
        if (from == null || to == null) return false;
        if (!(from.level() instanceof ServerLevel level)) return false;
        Vec3 start = from.getEyePosition();
        Vec3 end = to.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(
                start, end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                from
        ));
        // MISS or hit beyond target → clear LOS. If block hit closer than target eyes, blocked.
        if (hit.getType() == HitResult.Type.MISS) return true;
        double hitDist = hit.getLocation().distanceToSqr(start);
        double targetDist = end.distanceToSqr(start);
        // Allow small epsilon so grazing the target AABB doesn't count as blocked.
        return hitDist + 0.25 >= targetDist;
    }

    public static List<ServerPlayer> collectAlivePlayers(ServerLevel level) {
        List<ServerPlayer> out = new java.util.ArrayList<>();
        if (level == null) return out;

        List<UUID> blackoutAlive = BlackoutRoleManager.getAllAlive(level);
        if (!blackoutAlive.isEmpty() && level.getServer() != null) {
            for (UUID id : blackoutAlive) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
                if (p != null && !p.isSpectator()) out.add(p);
            }
            return out;
        }

        for (ServerPlayer p : level.players()) {
            if (p == null || p.isSpectator()) continue;
            try {
                if (GameUtils.isPlayerEliminated(p)) continue;
            } catch (Throwable ignored) {
            }
            out.add(p);
        }
        return out;
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putBoolean("Observing", observing);
        tag.putInt("ChargeTicks", chargeTicks);
        tag.putBoolean("Phase2", phase2);
        tag.putBoolean("DesireMarkUsed", desireMarkUsed);
        tag.put("DesireMarked", uuidList(desireMarked));
        tag.put("KnownLovers", uuidList(knownLovers));
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        observing = tag.getBoolean("Observing");
        chargeTicks = tag.getInt("ChargeTicks");
        phase2 = tag.getBoolean("Phase2");
        desireMarkUsed = tag.getBoolean("DesireMarkUsed");
        desireMarked.clear();
        desireMarked.addAll(readUuidList(tag, "DesireMarked"));
        knownLovers.clear();
        knownLovers.addAll(readUuidList(tag, "KnownLovers"));
    }

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        writeToSyncNbt(tag, registryLookup);
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        readFromSyncNbt(tag, registryLookup);
    }

    private static ListTag uuidList(Set<UUID> set) {
        ListTag list = new ListTag();
        for (UUID id : set) {
            list.add(StringTag.valueOf(id.toString()));
        }
        return list;
    }

    private static Set<UUID> readUuidList(CompoundTag tag, String key) {
        Set<UUID> out = new HashSet<>();
        if (!tag.contains(key, Tag.TAG_LIST)) return out;
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                out.add(UUID.fromString(list.getString(i)));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
