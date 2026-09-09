package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerPsychoComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.content.block.SmallDoorBlock;
import io.wifi.starrailexpress.content.block_entity.SmallDoorBlockEntity;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.index.TMMSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.agmas.harpymodloader.commands.RoleCountManager;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 暴怒：杀手方中立。杀手及杀手方中立队友死亡时积累愤怒；每达到一轮
 * {@code max(1, 开局杀手数 - 1)} 阈值就立即触发一次原版狂暴。
 *
 * <p>每层愤怒提供一级速度与急迫，效果等级最高为 III。狂暴期间获得
 * 近战免疫与完全击退抗性，并可左键直接撬开列车门。</p>
 */
public final class WrathComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<WrathComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_wrath"), WrathComponent.class);

    public static final int MAX_RAGE_EFFECT_STACKS = WrathPolicy.MAX_RAGE_EFFECT_STACKS;
    private static final int RAGE_EFFECT_REFRESH_TICKS = 40;
    private static final AttributeModifier BERSERK_KNOCKBACK_IMMUNITY = new AttributeModifier(
            HabiTrainCore.id("sin_wrath_berserk_knockback_immunity"),
            1.0D,
            AttributeModifier.Operation.ADD_VALUE);

    private final Player player;
    private final Set<UUID> countedTeammateDeaths = new HashSet<>();
    private int rage;
    private int teammateDeaths;
    private int berserkThreshold = 1;
    private int berserkTriggers;
    private boolean berserkActive;

    public WrathComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public int getRage() {
        return rage;
    }

    /** Compatibility accessor for the retired staged implementation. */
    public int getStage() {
        return effectStacksForRage(rage);
    }

    public int getEffectStacks() {
        return effectStacksForRage(rage);
    }

    public int getTeammateDeaths() {
        return teammateDeaths;
    }

    public int getBerserkThreshold() {
        return berserkThreshold;
    }

    public int getBerserkProgress() {
        return berserkThreshold <= 0 ? 0 : teammateDeaths % berserkThreshold;
    }

    public int getBerserkTriggers() {
        return berserkTriggers;
    }

    public boolean isBerserkActive() {
        return berserkActive;
    }

    public static int effectStacksForRage(int rage) {
        return WrathPolicy.effectStacksForRage(rage);
    }

    public static int berserkThresholdForKillerCount(int killerCount) {
        return WrathPolicy.berserkThresholdForKillerCount(killerCount);
    }

    /** True killers and killer-share neutrals are Wrath's teammates. */
    public static boolean isKillerAlly(SRERole role) {
        return role != null && (role.canUseKiller() || role.isNeutralForKiller());
    }

    /** Freeze the round's trigger threshold once the participating roster is final. */
    public static void onRoundReady(ServerLevel level) {
        if (level == null || SevenSins.WRATH == null) return;
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || !game.isRunning()) return;

        int participants = Math.max(0, GameUtils.getParticipatingPlayerCount(level));
        int killerCount;
        try {
            killerCount = RoleCountManager.getKillerCount(participants);
        } catch (Throwable t) {
            killerCount = participants / 6;
        }
        int threshold = berserkThresholdForKillerCount(killerCount);

        for (ServerPlayer self : level.players()) {
            if (self == null || self.isSpectator()) continue;
            if (!HabiRoles.isHabiRole(self, SevenSins.WRATH)) continue;
            WrathComponent component = KEY.get(self);
            if (component == null) continue;
            component.berserkThreshold = threshold;
            component.sync(self);
            HabiTrainCore.LOGGER.info(
                    "[Wrath] {} armed: participants={}, killers={}, threshold={}",
                    self.getGameProfile().getName(), participants, killerCount, threshold);
        }
    }

    /** Called from the managed any-death hook after a death has been confirmed. */
    public static void onAnyPlayerDeath(ServerPlayer dead) {
        if (dead == null || !(dead.level() instanceof ServerLevel level) || SevenSins.WRATH == null) return;
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || !game.isRunning()) return;

        SRERole deadRole = game.getRole(dead);
        if (!isKillerAlly(deadRole)) return;

        for (ServerPlayer self : level.players()) {
            if (self == null || self.isSpectator() || self.getUUID().equals(dead.getUUID())) continue;
            if (!HabiRoles.isHabiRole(self, SevenSins.WRATH)) continue;
            WrathComponent component = KEY.get(self);
            if (component != null) {
                component.recordTeammateDeath(self, dead);
            }
        }
    }

    private void recordTeammateDeath(ServerPlayer self, ServerPlayer dead) {
        if (!countedTeammateDeaths.add(dead.getUUID())) return;

        rage++;
        teammateDeaths++;
        int effectStacks = effectStacksForRage(rage);
        applyRageEffects(self, effectStacks);
        self.displayClientMessage(Component.translatable(
                "message.habitrain_core.sin_wrath.rage_gain",
                rage,
                effectStacks), true);

        int dueTriggers = teammateDeaths / Math.max(1, berserkThreshold);
        if (dueTriggers > berserkTriggers) {
            berserkTriggers = dueTriggers;
            startBerserk(self);
        } else {
            sync(self);
        }
    }

    private void startBerserk(ServerPlayer self) {
        SREPlayerPsychoComponent psycho = SREPlayerPsychoComponent.KEY.get(self);
        try {
            // A newly reached threshold refreshes an already-active frenzy immediately.
            if (psycho != null && psycho.getPsychoTicks() > 0) {
                psycho.stopPsychoAndSync();
            }
            berserkActive = SREPlayerShopComponent.usePsychoMode(self);
        } catch (Throwable t) {
            berserkActive = false;
            HabiTrainCore.LOGGER.warn("[Wrath] failed to start berserk for {}",
                    self.getGameProfile().getName(), t);
        }
        updateKnockbackImmunity(self, berserkActive);
        sync(self);
        if (berserkActive) {
            self.displayClientMessage(Component.translatable(
                    "message.habitrain_core.sin_wrath.berserk",
                    berserkTriggers), true);
        }
    }

    public InteractionResult tryPryDoorWithBat(ServerPlayer self, BlockPos clickedPos, InteractionHand hand) {
        if (self == null || clickedPos == null || hand == null) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND || !canPryDoorWithBat(self, clickedPos)
                || !self.canInteractWithBlock(clickedPos, 0.0D)
                || !self.level().getWorldBorder().isWithinBounds(clickedPos)) {
            return InteractionResult.PASS;
        }
        if (!(self.level() instanceof ServerLevel level)) return InteractionResult.PASS;

        BlockPos lowerPos = clickedPos;
        BlockEntity blockEntity = level.getBlockEntity(lowerPos);
        if (!(blockEntity instanceof SmallDoorBlockEntity)) {
            lowerPos = clickedPos.below();
            blockEntity = level.getBlockEntity(lowerPos);
        }
        if (!(blockEntity instanceof SmallDoorBlockEntity door)) {
            return InteractionResult.PASS;
        }

        if (!door.isBlasted()) {
            BlockState state = level.getBlockState(lowerPos);
            level.playSound(null, clickedPos, TMMSounds.ITEM_CROWBAR_PRY,
                    SoundSource.BLOCKS, 2.5F, 0.85F);
            self.swing(hand, true);
            if (state.getBlock() instanceof SmallDoorBlock smallDoor && !smallDoor.isOpen(state)) {
                smallDoor.open(state, level, door, lowerPos);
            }
            door.blast();
            if (SRE.REPLAY_MANAGER != null) {
                SRE.REPLAY_MANAGER.recordDoorPry(self.getUUID(), lowerPos);
            }
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_wrath.door_pry"), true);
        }
        return InteractionResult.SUCCESS;
    }

    public static boolean canPryDoorWithBat(Player self, BlockPos pos) {
        return self != null && pos != null && isMeleeImmune(self)
                && !SlothComponent.isSleepingSloth(self)
                && self.getMainHandItem().is(io.wifi.starrailexpress.index.TMMItems.BAT)
                && self.level().getBlockState(pos).getBlock() instanceof SmallDoorBlock;
    }

    public static boolean isMeleeImmune(Player self) {
        return self != null && !self.isSpectator() && HabiRoles.isHabiRole(self, SevenSins.WRATH)
                && SREGameWorldComponent.KEY.get(self.level()).isRunning() && isPsychoActive(self);
    }

    private static boolean isPsychoActive(Player self) {
        try {
            SREPlayerPsychoComponent psycho = SREPlayerPsychoComponent.KEY.get(self);
            return psycho != null && psycho.getPsychoTicks() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyRageEffects(ServerPlayer self, int stacks) {
        if (self == null || stacks <= 0) return;
        int amplifier = stacks - 1;
        self.addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SPEED, RAGE_EFFECT_REFRESH_TICKS, amplifier,
                true, false, true));
        self.addEffect(new MobEffectInstance(
                MobEffects.DIG_SPEED, RAGE_EFFECT_REFRESH_TICKS, amplifier,
                true, false, true));
    }

    private static void updateKnockbackImmunity(ServerPlayer self, boolean enabled) {
        if (self == null) return;
        var attribute = self.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (attribute == null) return;
        attribute.removeModifier(BERSERK_KNOCKBACK_IMMUNITY);
        if (enabled) {
            attribute.addTransientModifier(BERSERK_KNOCKBACK_IMMUNITY);
        }
    }

    private void sync(ServerPlayer self) {
        KEY.sync(self);
    }

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        countedTeammateDeaths.clear();
        rage = 0;
        teammateDeaths = 0;
        berserkThreshold = 1;
        berserkTriggers = 0;
        berserkActive = false;
        if (player instanceof ServerPlayer self) {
            updateKnockbackImmunity(self, false);
        }
    }

    @Override
    public void serverTick() {
        if (player.level().isClientSide || !(player instanceof ServerPlayer self)) return;
        if (!(self.level() instanceof ServerLevel level)) return;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        boolean isWrath = game != null && HabiRoles.isHabiRole(self, SevenSins.WRATH);
        if (!isWrath || self.isSpectator()) {
            if (berserkActive) {
                berserkActive = false;
                updateKnockbackImmunity(self, false);
                sync(self);
            }
            return;
        }

        if (rage > 0 && level.getGameTime() % 20L == 0L) {
            applyRageEffects(self, effectStacksForRage(rage));
        }

        boolean activeNow = isPsychoActive(self);
        if (activeNow != berserkActive) {
            berserkActive = activeNow;
            updateKnockbackImmunity(self, activeNow);
            sync(self);
        } else if (activeNow) {
            // Reassert after other effects/modifiers touch the attribute.
            updateKnockbackImmunity(self, true);
        }
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putInt("Rage", rage);
        tag.putInt("TeammateDeaths", teammateDeaths);
        tag.putInt("BerserkThreshold", berserkThreshold);
        tag.putInt("BerserkTriggers", berserkTriggers);
        tag.putBoolean("BerserkActive", berserkActive);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        rage = Math.max(0, tag.getInt("Rage"));
        teammateDeaths = Math.max(0, tag.getInt("TeammateDeaths"));
        berserkThreshold = Math.max(1, tag.getInt("BerserkThreshold"));
        berserkTriggers = Math.max(0, tag.getInt("BerserkTriggers"));
        berserkActive = tag.getBoolean("BerserkActive");
    }

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        // Round-only state is synchronized but never persisted to playerdata.
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        // Ignore stale state from older role implementations.
    }
}
