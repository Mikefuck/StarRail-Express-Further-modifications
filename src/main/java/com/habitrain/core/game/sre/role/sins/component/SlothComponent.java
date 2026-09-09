package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutVictoryChecker;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.win.SinVictoryHooks;
import com.habitrain.core.game.sre.role.sins.win.SlothWinPolicy;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREArmorPlayerComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.game.roles.innocence.noise_maker.NoiseMakerPlayerComponent;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 懒惰·贝露菲格露重做版。
 *
 * <p>组件挂在所有玩家身上：普通玩家保存睡觉任务次数、下一任务强制标记与被沉睡状态；
 * 懒惰本人额外负责技能和三人沉睡后的上床胜利判定。</p>
 */
public final class SlothComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<SlothComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_sloth"), SlothComponent.class);

    public static final double DROWSINESS_RADIUS = 5.0D;
    public static final int REQUIRED_SLEEP_TASKS = 2;
    public static final int REQUIRED_SIMULTANEOUS_SLEEPERS = 3;

    private final Player player;

    private boolean forceNextSleepTask;
    private int completedSleepTasks;
    private boolean inducedSleeping;
    private UUID inducedBySloth;
    private boolean slothWinTriggered;
    private double sleepX;
    private double sleepY;
    private double sleepZ;
    private float sleepYaw;
    private float sleepPitch;
    private BlockPos sleepPos = BlockPos.ZERO;

    public SlothComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public boolean hasForcedSleepTask() {
        return forceNextSleepTask;
    }

    /** Consumes the one-shot task override only after a real SLEEP instance was created. */
    public boolean consumeForcedSleepTask() {
        if (!forceNextSleepTask) {
            return false;
        }
        forceNextSleepTask = false;
        sync();
        return true;
    }

    public int getCompletedSleepTasks() {
        return completedSleepTasks;
    }

    public boolean isEligibleSleepTarget() {
        return completedSleepTasks >= REQUIRED_SLEEP_TASKS && !inducedSleeping;
    }

    public boolean isInducedSleeping() {
        return inducedSleeping;
    }

    public UUID getInducedBySloth() {
        return inducedBySloth;
    }

    /** G skill: mark every other living player within five blocks. */
    public static boolean useDrowsiness(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator() || !(self.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!canAct(self, level)) {
            return false;
        }

        double radiusSq = DROWSINESS_RADIUS * DROWSINESS_RADIUS;
        int affected = 0;
        for (ServerPlayer target : level.players()) {
            if (target == self || target.isSpectator() || target.distanceToSqr(self) > radiusSq) {
                continue;
            }
            try {
                if (!GameUtils.isPlayerAliveAndSurvival(target)) {
                    continue;
                }
                SlothComponent component = KEY.get(target);
                component.forceNextSleepTask = true;
                component.sync();
                target.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_sloth.task_forced"), true);
                affected++;
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Sloth] failed to force sleep task for {}",
                        target.getGameProfile().getName(), t);
            }
        }

        if (affected <= 0) {
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_sloth.no_nearby_players"), true);
            return false;
        }
        self.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_sloth.skill_success", affected), true);
        return true;
    }

    /** Called once by the centralized finish-quest path for every player's completed task. */
    public static void onAnyTaskFinished(Player taskPlayer, String quest) {
        if (!(taskPlayer instanceof ServerPlayer serverPlayer) || !isSleepQuest(quest)
                || !isRoundRunning(serverPlayer.serverLevel())
                || !GameUtils.isPlayerAliveAndSurvival(serverPlayer)
                || isSleepingSloth(serverPlayer)) {
            return;
        }
        try {
            SlothComponent component = KEY.get(serverPlayer);
            component.completedSleepTasks++;
            component.sync();
            if (component.completedSleepTasks == REQUIRED_SLEEP_TASKS) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_sloth.target_unlocked"), true);
            }
            HabiTrainCore.LOGGER.info("[Sloth] {} completed sleep task count={}",
                    serverPlayer.getGameProfile().getName(), component.completedSleepTasks);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Sloth] sleep task completion tracking failed", t);
        }
    }

    private static boolean isSleepQuest(String quest) {
        if (quest == null || quest.isBlank()) {
            return false;
        }
        String normalized = quest.trim();
        int colon = normalized.indexOf(':');
        if (colon >= 0 && colon + 1 < normalized.length()) {
            normalized = normalized.substring(colon + 1);
        }
        return "sleep".equalsIgnoreCase(normalized);
    }

    /** Server-authoritative roster used by the backpack selector. */
    public static List<ServerPlayer> eligibleTargets(ServerPlayer sloth) {
        if (sloth == null || !(sloth.level() instanceof ServerLevel level) || !canAct(sloth, level)) {
            return List.of();
        }
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer target : level.players()) {
            if (target == sloth || target.isSpectator()) {
                continue;
            }
            try {
                if (!GameUtils.isPlayerAliveAndSurvival(target)) {
                    continue;
                }
                SlothComponent component = KEY.get(target);
                if (component.isEligibleSleepTarget()) {
                    result.add(target);
                }
            } catch (Throwable ignored) {
            }
        }
        result.sort(Comparator.comparing(p -> p.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    /** Validates the clicked roster entry again and puts that player into endless induced sleep. */
    public static boolean tryInduceSleep(ServerPlayer sloth, UUID targetId) {
        if (sloth == null || targetId == null || !(sloth.level() instanceof ServerLevel level)
                || !canAct(sloth, level)) {
            return false;
        }
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(targetId);
        if (target == null || target == sloth || target.level() != level || target.isSpectator()) {
            sloth.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_sloth.target_invalid"), true);
            return false;
        }
        try {
            if (!GameUtils.isPlayerAliveAndSurvival(target)) {
                sloth.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_sloth.target_invalid"), true);
                return false;
            }
            SlothComponent component = KEY.get(target);
            if (!component.isEligibleSleepTarget()) {
                sloth.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_sloth.target_invalid"), true);
                return false;
            }
            component.enterInducedSleep(target, sloth);
            return true;
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Sloth] failed to induce sleep target={}", targetId, t);
            sloth.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_sloth.target_invalid"), true);
            return false;
        }
    }

    private void enterInducedSleep(ServerPlayer target, ServerPlayer sloth) {
        inducedSleeping = true;
        inducedBySloth = sloth.getUUID();
        target.closeContainer();
        target.stopUsingItem();
        captureSleepAnchor(target);
        SREArmorPlayerComponent.KEY.get(target).addArmor();
        ensureSleepingPose(target);
        sleepX = target.getX();
        sleepY = target.getY();
        sleepZ = target.getZ();
        sync();
        target.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_sloth.enter_sleep"), true);
        sloth.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_sloth.target_slept",
                        target.getGameProfile().getName()), true);
        HabiTrainCore.LOGGER.info("[Sloth] {} induced endless sleep on {}",
                sloth.getGameProfile().getName(), target.getGameProfile().getName());
    }

    /** Called after the granted armor layer is consumed by the upstream shield pipeline. */
    public static void onShieldBroken(Player victim) {
        if (!(victim instanceof ServerPlayer serverVictim)) {
            return;
        }
        try {
            SlothComponent component = KEY.get(serverVictim);
            if (component.inducedSleeping) {
                component.wakeFromShieldBreak(serverVictim);
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Sloth] wake after shield break failed", t);
        }
    }

    private void wakeFromShieldBreak(ServerPlayer target) {
        inducedSleeping = false;
        inducedBySloth = null;
        target.stopSleeping();
        sync();
        target.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_sloth.wake_anger"), true);

        // Reuse the real upstream Noisemaker ability: sound, glow, shockwave, stun and voice range stay identical.
        try {
            NoiseMakerPlayerComponent.KEY.get(target).useAbility();
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Sloth] failed to trigger Noisemaker wake-up ability for {}",
                    target.getGameProfile().getName(), t);
        }
        HabiTrainCore.LOGGER.info("[Sloth] {} woke after shield break and triggered Noisemaker ability",
                target.getGameProfile().getName());
    }

    /** Compatibility name retained for existing lock hooks; now means any induced sleeper. */
    public static boolean isSleepingSloth(Player target) {
        if (target == null) {
            return false;
        }
        try {
            return KEY.get(target).inducedSleeping;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isSlothPlayer(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null || SevenSins.SLOTH == null) {
            return false;
        }
        try {
            return HabiRoles.isHabiRole(player, SevenSins.SLOTH);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean canAct(ServerPlayer player, ServerLevel level) {
        return isRoundRunning(level) && isSlothPlayer(level, player)
                && GameUtils.isPlayerAliveAndSurvival(player) && !isSleepingSloth(player);
    }

    public static int countInducedSleepers(ServerLevel level, UUID slothId) {
        if (level == null || slothId == null) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer candidate : level.players()) {
            try {
                SlothComponent component = KEY.get(candidate);
                if (component.inducedSleeping && slothId.equals(component.inducedBySloth)
                        && !candidate.isSpectator() && GameUtils.isPlayerAliveAndSurvival(candidate)) {
                    count++;
                }
            } catch (Throwable ignored) {
            }
        }
        return count;
    }

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        boolean wasSleeping = inducedSleeping;
        inducedSleeping = false;
        if (wasSleeping && player.isSleeping()) {
            player.stopSleeping();
        }
        forceNextSleepTask = false;
        completedSleepTasks = 0;
        inducedSleeping = false;
        inducedBySloth = null;
        slothWinTriggered = false;
        sleepX = sleepY = sleepZ = 0.0D;
        sleepYaw = sleepPitch = 0.0F;
        sleepPos = BlockPos.ZERO;
        sync();
    }

    @Override
    public void serverTick() {
        if (!(player instanceof ServerPlayer self) || !(self.level() instanceof ServerLevel level)) {
            return;
        }

        if (!isRoundRunning(level)) {
            if (inducedSleeping || forceNextSleepTask || completedSleepTasks != 0 || slothWinTriggered) {
                clear();
            }
            return;
        }

        if (inducedSleeping) {
            if (!GameUtils.isPlayerAliveAndSurvival(self)) {
                releaseWithoutWakeAbility(self);
            } else {
                lockInducedSleeper(self);
            }
        }

        if (isSlothPlayer(level, self)) {
            checkSlothBedWin(self, level);
        }
    }

    private static boolean isRoundRunning(ServerLevel level) {
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            return game != null && game.isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    private void lockInducedSleeper(ServerPlayer self) {
        self.setDeltaMovement(Vec3.ZERO);
        self.hurtMarked = true;
        self.hasImpulse = true;
        try {
            self.setJumping(false);
        } catch (Throwable ignored) {
        }
        if (self.distanceToSqr(sleepX, sleepY, sleepZ) > 0.04D
                || self.getYRot() != sleepYaw || self.getXRot() != sleepPitch) {
            self.teleportTo(sleepX, sleepY, sleepZ);
            self.setYRot(sleepYaw);
            self.setXRot(sleepPitch);
        }
        ensureSleepingPose(self);
    }

    private void ensureSleepingPose(ServerPlayer self) {
        if (!self.isSleeping()) {
            self.startSleeping(sleepPos);
        }
    }

    private void releaseWithoutWakeAbility(ServerPlayer self) {
        inducedSleeping = false;
        inducedBySloth = null;
        if (self.isSleeping()) {
            self.stopSleeping();
        }
        sync();
    }

    private void checkSlothBedWin(ServerPlayer self, ServerLevel level) {
        if (slothWinTriggered || self.isSpectator() || inducedSleeping) {
            return;
        }
        int sleepers = countInducedSleepers(level, self.getUUID());
        boolean lyingInRealBed = isLyingInRealBed(self, level);
        if (!SlothWinPolicy.shouldDeclare(sleepers, lyingInRealBed,
                GameUtils.isPlayerAliveAndSurvival(self), slothWinTriggered)) {
            return;
        }

        slothWinTriggered = true;
        sync();
        self.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_sloth.win_ready"), true);
        if (com.habitrain.core.api.GameModeRegistry.getActiveForLevel(level)
                .filter(mode -> mode instanceof com.habitrain.core.game.blackout.BlackoutMode).isPresent()) {
            BlackoutVictoryChecker.endGameSlothCustom(level, self);
        } else {
            SinVictoryHooks.triggerSlothWin(level, self);
        }
    }

    private static boolean isLyingInRealBed(ServerPlayer self, ServerLevel level) {
        if (!self.isSleeping()) {
            return false;
        }
        return self.getSleepingPos()
                .map(pos -> level.getBlockState(pos).is(BlockTags.BEDS))
                .orElse(false);
    }

    private void captureSleepAnchor(ServerPlayer self) {
        sleepX = self.getX();
        sleepY = self.getY();
        sleepZ = self.getZ();
        sleepYaw = self.getYRot();
        sleepPitch = self.getXRot();
        sleepPos = self.blockPosition();
    }

    private void sync() {
        try {
            KEY.sync(player);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putBoolean("ForceNextSleepTask", forceNextSleepTask);
        tag.putInt("CompletedSleepTasks", completedSleepTasks);
        tag.putBoolean("InducedSleeping", inducedSleeping);
        if (inducedBySloth != null) {
            tag.putUUID("InducedBySloth", inducedBySloth);
        }
        tag.putBoolean("SlothWinTriggered", slothWinTriggered);
        tag.putDouble("SleepX", sleepX);
        tag.putDouble("SleepY", sleepY);
        tag.putDouble("SleepZ", sleepZ);
        tag.putFloat("SleepYaw", sleepYaw);
        tag.putFloat("SleepPitch", sleepPitch);
        tag.putLong("SleepPos", sleepPos.asLong());
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        forceNextSleepTask = tag.getBoolean("ForceNextSleepTask");
        completedSleepTasks = tag.getInt("CompletedSleepTasks");
        inducedSleeping = tag.getBoolean("InducedSleeping");
        inducedBySloth = tag.hasUUID("InducedBySloth") ? tag.getUUID("InducedBySloth") : null;
        slothWinTriggered = tag.getBoolean("SlothWinTriggered");
        sleepX = tag.getDouble("SleepX");
        sleepY = tag.getDouble("SleepY");
        sleepZ = tag.getDouble("SleepZ");
        sleepYaw = tag.getFloat("SleepYaw");
        sleepPitch = tag.getFloat("SleepPitch");
        sleepPos = tag.contains("SleepPos") ? BlockPos.of(tag.getLong("SleepPos")) : BlockPos.ZERO;
    }

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        // Round-local state only.
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        // Ignore stale playerdata from older role implementations.
    }
}
