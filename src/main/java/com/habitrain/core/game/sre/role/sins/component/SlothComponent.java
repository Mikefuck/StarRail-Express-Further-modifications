package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 懒惰：安全时正常；安全结束沉睡 + 护盾；破盾狂暴；整局一次主动醒爆炸。
 * <p>
 * 护盾使用内部计数（不依赖 {@code SREArmorPlayerComponent} 多叠语义）。
 */
public final class SlothComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<SlothComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_sloth"), SlothComponent.class);

    public static final int SHIELD_BREAK_BERSERK_TICKS = 200; // 10s
    public static final int ACTIVE_BERSERK_TICKS = 600; // 30s
    public static final double EXPLODE_RADIUS = 5.0;
    public static final int KILLS_PER_SHIELD = 2;
    public static final ResourceLocation SLOTH_EXPLODE = HabiTrainCore.id("sloth_explode");

    private final Player player;

    private boolean sleeping;
    private int shields;
    private final Set<UUID> attackers = new HashSet<>();
    private boolean onceAwakeUsed;
    private long berserkUntilGameTime;
    private int killsInBerserk;
    /** true = open berserk (skill); false = limited to attackers (shield break). */
    private boolean openBerserk;
    private boolean enteredSleepThisRound;

    public SlothComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public boolean isSleeping() {
        return sleeping;
    }

    public int getShields() {
        return shields;
    }

    public Set<UUID> getAttackers() {
        return attackers;
    }

    public boolean isOnceAwakeUsed() {
        return onceAwakeUsed;
    }

    public boolean isBerserk(ServerLevel level) {
        if (level == null) return false;
        return level.getGameTime() < berserkUntilGameTime;
    }

    public boolean isOpenBerserk(ServerLevel level) {
        return isBerserk(level) && openBerserk;
    }

    public boolean canAttackTarget(ServerLevel level, UUID targetId) {
        if (targetId == null) return false;
        if (!isBerserk(level)) return false;
        if (openBerserk) return true;
        return attackers.contains(targetId);
    }

    public boolean isInputLocked() {
        return sleeping;
    }

    /** Enter sleep with shields = ceil(alive/2). Clears attackers. */
    public void enterSleep(ServerPlayer self, ServerLevel level) {
        if (self == null || level == null) return;
        int alive = countAlive(level);
        int nextShields = Math.max(1, (alive + 1) / 2); // ceil(alive/2), min 1
        sleeping = true;
        shields = nextShields;
        attackers.clear();
        berserkUntilGameTime = 0;
        killsInBerserk = 0;
        openBerserk = false;
        enteredSleepThisRound = true;
        KEY.sync(self);
        self.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_sloth.sleep", shields),
                true
        );
        HabiTrainCore.LOGGER.info("[Sloth] {} enter sleep shields={} alive={}",
                self.getGameProfile().getName(), shields, alive);
    }

    /**
     * Conventional hit while sleeping: absorb with shield, record attacker.
     *
     * @return {@code false} to cancel death; {@code true} allow death (not sleeping / no shields).
     */
    public boolean onShieldHit(ServerPlayer self, ServerPlayer attacker) {
        if (self == null || !sleeping) return true;
        if (!(self.level() instanceof ServerLevel level)) return true;

        if (attacker != null && !attacker.getUUID().equals(self.getUUID())) {
            attackers.add(attacker.getUUID());
        }

        if (shields > 0) {
            shields--;
        }

        self.setHealth(self.getMaxHealth());

        if (shields <= 0) {
            shields = 0;
            wakeBerserk(self, level, SHIELD_BREAK_BERSERK_TICKS, false);
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_sloth.shield_break"),
                    true
            );
        } else {
            KEY.sync(self);
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_sloth.shield_hit", shields),
                    true
            );
            if (attacker != null) {
                attacker.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_sloth.attacker_hit", shields),
                        true
                );
            }
        }
        return false;
    }

    private void wakeBerserk(ServerPlayer self, ServerLevel level, int ticks, boolean open) {
        sleeping = false;
        openBerserk = open;
        killsInBerserk = 0;
        berserkUntilGameTime = level.getGameTime() + ticks;
        KEY.sync(self);
        self.displayClientMessage(
                Component.translatable(
                        open
                                ? "message.habitrain_core.sin_sloth.berserk_open"
                                : "message.habitrain_core.sin_sloth.berserk_limited",
                        ticks / 20
                ),
                true
        );
        HabiTrainCore.LOGGER.info("[Sloth] {} wake berserk open={} ticks={}",
                self.getGameProfile().getName(), open, ticks);
    }

    /** Called when sloth kills someone during berserk. */
    public void onBerserkKill(ServerPlayer self) {
        if (self == null || !(self.level() instanceof ServerLevel level)) return;
        if (!isBerserk(level)) return;
        killsInBerserk++;
        if (openBerserk && killsInBerserk > 0 && killsInBerserk % KILLS_PER_SHIELD == 0) {
            shields++;
            self.displayClientMessage(
                    Component.translatable(
                            "message.habitrain_core.sin_sloth.berserk_shield",
                            shields, killsInBerserk
                    ),
                    true
            );
        } else {
            self.displayClientMessage(
                    Component.translatable(
                            "message.habitrain_core.sin_sloth.berserk_kill",
                            killsInBerserk
                    ),
                    true
            );
        }
        KEY.sync(self);
    }

    /**
     * Once-per-game active skill: sleep + shield≥1 → consume shields, explode, open berserk 30s.
     */
    public static boolean useAwake(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!(self.level() instanceof ServerLevel level)) return false;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || SevenSins.SLOTH == null || !game.isRole(self, SevenSins.SLOTH)) {
            return false;
        }

        SlothComponent c = KEY.get(self);
        if (c == null) return false;
        if (c.onceAwakeUsed) {
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_sloth.skill_used"), true);
            return false;
        }
        if (!c.sleeping || c.shields < 1) {
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_sloth.skill_need_sleep"), true);
            return false;
        }

        c.onceAwakeUsed = true;
        c.shields = 0;
        c.attackers.clear();
        c.sleeping = false;
        explodeNearby(self, level);
        c.wakeBerserk(self, level, ACTIVE_BERSERK_TICKS, true);
        KEY.sync(self);
        self.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_sloth.skill_awake"),
                true
        );
        return true;
    }

    private static void explodeNearby(ServerPlayer self, ServerLevel level) {
        Vec3 origin = self.position();
        double r2 = EXPLODE_RADIUS * EXPLODE_RADIUS;
        ResourceLocation reason = SLOTH_EXPLODE;
        try {
            if (GameConstants.DeathReasons.GRENADE != null) {
                reason = GameConstants.DeathReasons.GRENADE;
            }
        } catch (Throwable ignored) {
        }

        for (ServerPlayer other : level.players()) {
            if (other == null || other == self) continue;
            if (other.isSpectator()) continue;
            try {
                if (GameUtils.isPlayerEliminated(other)) continue;
            } catch (Throwable ignored) {
            }
            if (other.distanceToSqr(origin) > r2) continue;
            try {
                GameUtils.killPlayer(other, true, self, reason);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Sloth] explode kill failed for {}",
                        other.getGameProfile().getName(), t);
            }
        }

        try {
            level.playSound(null, self.getX(), self.getY(), self.getZ(),
                    SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 4.0f, 1.0f);
        } catch (Throwable t) {
            try {
                level.playSound(null, self.getX(), self.getY(), self.getZ(),
                        SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 4.0f, 1.0f);
            } catch (Throwable ignored) {
            }
        }
        HabiTrainCore.LOGGER.info("[Sloth] {} active explode r={}",
                self.getGameProfile().getName(), EXPLODE_RADIUS);
    }

    private void endBerserkResleep(ServerPlayer self, ServerLevel level) {
        if (self == null || level == null) return;
        if (self.isSpectator()) {
            sleeping = false;
            berserkUntilGameTime = 0;
            openBerserk = false;
            KEY.sync(self);
            return;
        }
        int next = Math.max(1, shields);
        sleeping = true;
        shields = next;
        attackers.clear();
        killsInBerserk = 0;
        openBerserk = false;
        berserkUntilGameTime = 0;
        KEY.sync(self);
        self.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_sloth.resleep", shields),
                true
        );
        HabiTrainCore.LOGGER.info("[Sloth] {} re-sleep shields={}",
                self.getGameProfile().getName(), shields);
    }

    public static void onSafeTimeEnd(ServerLevel level) {
        if (level == null || SevenSins.SLOTH == null) return;
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || !game.isRunning()) return;

        for (ServerPlayer p : level.players()) {
            if (p == null || p.isSpectator()) continue;
            if (!game.isRole(p, SevenSins.SLOTH)) continue;
            try {
                SlothComponent c = KEY.get(p);
                if (c == null) continue;
                if (c.enteredSleepThisRound || c.sleeping || c.isBerserk(level)) continue;
                c.enterSleep(p, level);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Sloth] enterSleep failed for {}",
                        p.getGameProfile().getName(), t);
            }
        }
    }

    public static int countAlive(ServerLevel level) {
        if (level == null) return 0;
        java.util.List<UUID> blackoutAlive = BlackoutRoleManager.getAllAlive(level);
        if (!blackoutAlive.isEmpty()) {
            return blackoutAlive.size();
        }
        try {
            long c = GameUtils.getAlivePlayerCount(level);
            if (c > 0) return (int) c;
        } catch (Throwable ignored) {
        }
        int n = 0;
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        for (ServerPlayer p : level.players()) {
            if (p == null || p.isSpectator()) continue;
            if (game != null && game.getRole(p) == null) continue;
            try {
                if (GameUtils.isPlayerEliminated(p)) continue;
            } catch (Throwable ignored) {
            }
            n++;
        }
        return n;
    }

    public static boolean isSleepingSloth(Player p) {
        if (p == null) return false;
        try {
            SlothComponent c = KEY.get(p);
            return c != null && c.sleeping;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isSlothPlayer(ServerLevel level, ServerPlayer p) {
        if (level == null || p == null || SevenSins.SLOTH == null) return false;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            return game != null && game.isRole(p, SevenSins.SLOTH);
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
        sleeping = false;
        shields = 0;
        attackers.clear();
        onceAwakeUsed = false;
        berserkUntilGameTime = 0;
        killsInBerserk = 0;
        openBerserk = false;
        enteredSleepThisRound = false;
    }

    @Override
    public void serverTick() {
        if (player.level().isClientSide) return;
        if (!(player instanceof ServerPlayer self)) return;
        if (!(self.level() instanceof ServerLevel level)) return;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        boolean isSloth = game != null && SevenSins.SLOTH != null && game.isRole(self, SevenSins.SLOTH);
        if (!isSloth || self.isSpectator()) {
            if (sleeping) {
                sleeping = false;
            }
            return;
        }

        long now = level.getGameTime();

        // Berserk expiry → re-sleep with accumulated shields
        if (berserkUntilGameTime > 0 && now >= berserkUntilGameTime) {
            endBerserkResleep(self, level);
        }

        if (sleeping) {
            self.setDeltaMovement(Vec3.ZERO);
            self.hurtMarked = true;
            self.hasImpulse = true;
            try {
                self.setJumping(false);
            } catch (Throwable ignored) {
            }
            if (now % 5L == 0L) {
                self.teleportTo(self.getX(), self.getY(), self.getZ());
            }
            if (now % 40L == 0L) {
                self.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_sloth.sleeping_hud", shields),
                        true
                );
            }
        } else if (isBerserk(level) && now % 40L == 0L) {
            int remain = (int) Math.max(0, (berserkUntilGameTime - now + 19) / 20);
            self.displayClientMessage(
                    Component.translatable(
                            openBerserk
                                    ? "message.habitrain_core.sin_sloth.berserk_hud_open"
                                    : "message.habitrain_core.sin_sloth.berserk_hud_limited",
                            remain,
                            openBerserk ? shields : attackers.size()
                    ),
                    true
            );
        }
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putBoolean("Sleeping", sleeping);
        tag.putInt("Shields", shields);
        tag.putBoolean("OnceAwakeUsed", onceAwakeUsed);
        tag.putLong("BerserkUntil", berserkUntilGameTime);
        tag.putInt("KillsInBerserk", killsInBerserk);
        tag.putBoolean("OpenBerserk", openBerserk);
        tag.putBoolean("EnteredSleep", enteredSleepThisRound);
        ListTag list = new ListTag();
        for (UUID id : attackers) {
            list.add(StringTag.valueOf(id.toString()));
        }
        tag.put("Attackers", list);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        sleeping = tag.getBoolean("Sleeping");
        shields = tag.getInt("Shields");
        onceAwakeUsed = tag.getBoolean("OnceAwakeUsed");
        berserkUntilGameTime = tag.getLong("BerserkUntil");
        killsInBerserk = tag.getInt("KillsInBerserk");
        openBerserk = tag.getBoolean("OpenBerserk");
        enteredSleepThisRound = tag.getBoolean("EnteredSleep");
        attackers.clear();
        if (tag.contains("Attackers", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Attackers", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                try {
                    attackers.add(UUID.fromString(list.getString(i)));
                } catch (Throwable ignored) {
                }
            }
        }
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
