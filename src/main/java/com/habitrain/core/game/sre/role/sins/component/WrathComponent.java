package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.SinDeathReasons;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * 暴怒：好人常规武器致死推进愤怒阶段；阶段效果由组件每 tick 重施（奶/蜜无法清状态）；
 * 击杀降阶段；失智后 5 杀力竭。
 * <p>
 * 滤镜：无公开 ImmersiveFilter API 时用药水近似（红=黑暗脉冲+actionbar；黑白=黑暗+夜视）。
 */
public final class WrathComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<WrathComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_wrath"), WrathComponent.class);

    public static final int STAGE_CAP = 5;
    public static final int SPEED_STACK_CAP = 5;
    public static final int FRENZY_KILL_CAP = 5;
    public static final int ROOT_TICKS = 20 * 3;
    public static final int EFFECT_REFRESH_TICKS = 40;
    public static final ResourceLocation WRATH_EXHAUSTION =
            HabiTrainCore.id("wrath_exhaustion");
    public static final ResourceLocation FAKE_KNIFE_ID =
            ResourceLocation.parse("noellesroles:fake_knife");
    public static final ResourceLocation FAKE_REVOLVER_ID =
            ResourceLocation.parse("noellesroles:fake_revolver");
    public static final String TAG_WRATH_BAT = "habitrain_wrath_bat";

    private final Player player;

    /** 0 = 未进阶段；1–5 = 已递进次数（满 5 后叠速度）。 */
    private int stage;
    private int speedStacks;
    private boolean enteredFrenzy;
    private int killsAfterFrenzy;
    private boolean batGiven;
    private long rootUntilGameTime;
    private boolean exhausted;

    public WrathComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public int getStage() {
        return stage;
    }

    public int getSpeedStacks() {
        return speedStacks;
    }

    public boolean isEnteredFrenzy() {
        return enteredFrenzy;
    }

    public int getKillsAfterFrenzy() {
        return killsAfterFrenzy;
    }

    public boolean isExhausted() {
        return exhausted;
    }

    /** Default loadout: fake knife + fake revolver (noelles), with wooden-sword fallback. */
    public static List<ItemStack> defaultItems() {
        List<ItemStack> items = new ArrayList<>(2);
        ItemStack knife = HabiRoleItems.lookupItem(FAKE_KNIFE_ID, 1);
        if (knife.isEmpty()) {
            knife = new ItemStack(Items.WOODEN_SWORD);
            knife.set(DataComponents.CUSTOM_NAME, Component.literal("假刀"));
        }
        items.add(knife);

        ItemStack gun = HabiRoleItems.lookupItem(FAKE_REVOLVER_ID, 1);
        if (gun.isEmpty()) {
            gun = new ItemStack(Items.STONE_HOE);
            gun.set(DataComponents.CUSTOM_NAME, Component.literal("假枪"));
        }
        items.add(gun);
        return items;
    }

    /**
     * @return {@code false} to cancel death (stage advanced / speed stacked);
     *         {@code true} to allow death.
     */
    public boolean onLethalFromGoodWeapon(ServerPlayer self, ServerPlayer attacker) {
        if (self == null || exhausted) return true;
        if (!(self.level() instanceof ServerLevel level)) return true;

        if (stage < STAGE_CAP) {
            stage++;
            applyStageTransition(self, level, stage);
            KEY.sync(self);
            self.setHealth(self.getMaxHealth());
            HabiTrainCore.LOGGER.debug("[Wrath] stage advance {} -> stage={} by {}",
                    self.getGameProfile().getName(), stage, attacker.getGameProfile().getName());
            return false;
        }

        if (speedStacks < SPEED_STACK_CAP) {
            speedStacks++;
            reapplyEffects(self, level);
            KEY.sync(self);
            self.setHealth(self.getMaxHealth());
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_wrath.speed", speedStacks, SPEED_STACK_CAP),
                    true
            );
            HabiTrainCore.LOGGER.debug("[Wrath] speed stack {} -> {}/{}",
                    self.getGameProfile().getName(), speedStacks, SPEED_STACK_CAP);
            return false;
        }

        // stage >= 5 and speedStacks >= 5 → death allowed
        return true;
    }

    /** Called when this wrath player kills someone (OnPlayerDeathWithKiller). */
    public void onWrathKill(ServerPlayer self) {
        if (self == null || exhausted) return;
        if (!(self.level() instanceof ServerLevel level)) return;

        if (stage > 0) {
            stage = Math.max(0, stage - 1);
        }
        if (stage < STAGE_CAP) {
            // Drop below frenzy threshold: keep enteredFrenzy flag for kill counter,
            // but speed stacks only matter while stage >= 5.
        }

        if (enteredFrenzy) {
            killsAfterFrenzy++;
            self.displayClientMessage(
                    Component.translatable(
                            "message.habitrain_core.sin_wrath.frenzy_kill",
                            killsAfterFrenzy, FRENZY_KILL_CAP
                    ),
                    true
            );
            if (killsAfterFrenzy >= FRENZY_KILL_CAP) {
                exhausted = true;
                KEY.sync(self);
                HabiTrainCore.LOGGER.info("[Wrath] {} frenzy exhaustion after {} kills",
                        self.getGameProfile().getName(), killsAfterFrenzy);
                // Force path so AllowPlayerDeathWithKiller stage machine cannot cancel.
                try {
                    GameUtils.forceKillPlayer(self, true, null, WRATH_EXHAUSTION);
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[Wrath] forceKillPlayer failed, fallback killPlayer", t);
                    GameUtils.killPlayer(self, true, null, WRATH_EXHAUSTION);
                }
                return;
            }
        }

        reapplyEffects(self, level);
        KEY.sync(self);
        self.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_wrath.stage_down", stage),
                true
        );
    }

    private void applyStageTransition(ServerPlayer self, ServerLevel level, int newStage) {
        switch (newStage) {
            case 1 -> {
                rootUntilGameTime = level.getGameTime() + ROOT_TICKS;
                self.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_wrath.stage1"), true);
            }
            case 2 -> {
                giveBat(self);
                self.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_wrath.stage2"), true);
            }
            case 3 -> self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_wrath.stage3"), true);
            case 4 -> self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_wrath.stage4"), true);
            case 5 -> {
                enteredFrenzy = true;
                self.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_wrath.stage5"), true);
            }
            default -> {
            }
        }
        reapplyEffects(self, level);
    }

    private void giveBat(ServerPlayer self) {
        if (batGiven) return;
        batGiven = true;
        ItemStack bat;
        try {
            bat = TMMItems.BAT.getDefaultInstance();
        } catch (Throwable t) {
            bat = ItemStack.EMPTY;
        }
        if (bat == null || bat.isEmpty()) {
            bat = new ItemStack(Items.WOODEN_SWORD);
            bat.set(DataComponents.CUSTOM_NAME, Component.literal("棒球棍"));
        }
        HabiRoleItems.putFlag(bat, TAG_WRATH_BAT, true);
        if (!self.getInventory().add(bat)) {
            self.drop(bat, false);
        }
    }

    /**
     * Re-apply stage visuals/effects from component state (milk cannot clear flags).
     */
    public void reapplyEffects(ServerPlayer self, ServerLevel level) {
        if (self == null || level == null || exhausted) return;
        long now = level.getGameTime();

        // Stage 1 root window (heavy slowness ~3s from transition)
        if (stage >= 1 && now < rootUntilGameTime) {
            int remain = (int) Math.min(Integer.MAX_VALUE, rootUntilGameTime - now);
            self.addEffect(new MobEffectInstance(
                    MobEffects.MOVEMENT_SLOWDOWN, Math.max(remain, 10), 6, false, true, true));
        }

        // Stage 2+: red filter approximation (darkness pulse + periodic actionbar)
        if (stage >= 2) {
            self.addEffect(new MobEffectInstance(
                    MobEffects.DARKNESS, EFFECT_REFRESH_TICKS + 5, 0, false, false, true));
            if (now % 40L == 0L) {
                self.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_wrath.filter_red"), true);
            }
        }

        // Stage 3+: B&W approximation (darkness + night vision; no public desaturate shader)
        if (stage >= 3) {
            self.addEffect(new MobEffectInstance(
                    MobEffects.NIGHT_VISION, EFFECT_REFRESH_TICKS + 5, 0, false, false, true));
            if (now % 60L == 0L) {
                self.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_wrath.filter_bw"), true);
            }
        }

        // Stage 4+: darkness + blindness
        if (stage >= 4) {
            self.addEffect(new MobEffectInstance(
                    MobEffects.DARKNESS, EFFECT_REFRESH_TICKS + 5, 1, false, false, true));
            self.addEffect(new MobEffectInstance(
                    MobEffects.BLINDNESS, EFFECT_REFRESH_TICKS + 5, 0, false, false, true));
        }

        // Stage 5+: nausea + frenzy
        if (stage >= 5) {
            enteredFrenzy = true;
            self.addEffect(new MobEffectInstance(
                    MobEffects.CONFUSION, EFFECT_REFRESH_TICKS + 5, 0, false, false, true));
        }

        // Post-stage-5 speed stacks
        if (stage >= STAGE_CAP && speedStacks > 0) {
            int amp = Math.max(0, speedStacks - 1);
            self.addEffect(new MobEffectInstance(
                    MobEffects.MOVEMENT_SPEED, EFFECT_REFRESH_TICKS + 5, amp, false, true, true));
        }
    }

    /**
     * GOOD/innocent attacker for stage advance = not {@code canUseKiller}
     * (matches MercyVirtue + task brief).
     */
    public static boolean isInnocentAttacker(ServerLevel level, ServerPlayer killer) {
        if (level == null || killer == null) return false;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null) return false;
            SRERole role = game.getRole(killer);
            if (role == null) return false;
            return !role.canUseKiller();
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
        stage = 0;
        speedStacks = 0;
        enteredFrenzy = false;
        killsAfterFrenzy = 0;
        batGiven = false;
        rootUntilGameTime = 0;
        exhausted = false;
    }

    @Override
    public void serverTick() {
        if (player.level().isClientSide) return;
        if (!(player instanceof ServerPlayer self)) return;
        if (!(self.level() instanceof ServerLevel level)) return;
        if (self.isSpectator() || exhausted) return;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || SevenSins.WRATH == null || !game.isRole(self, SevenSins.WRATH)) {
            return;
        }
        if (stage <= 0 && speedStacks <= 0 && !enteredFrenzy) {
            return;
        }
        reapplyEffects(self, level);
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putInt("Stage", stage);
        tag.putInt("SpeedStacks", speedStacks);
        tag.putBoolean("EnteredFrenzy", enteredFrenzy);
        tag.putInt("KillsAfterFrenzy", killsAfterFrenzy);
        tag.putBoolean("BatGiven", batGiven);
        tag.putLong("RootUntil", rootUntilGameTime);
        tag.putBoolean("Exhausted", exhausted);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        stage = tag.getInt("Stage");
        speedStacks = tag.getInt("SpeedStacks");
        enteredFrenzy = tag.getBoolean("EnteredFrenzy");
        killsAfterFrenzy = tag.getInt("KillsAfterFrenzy");
        batGiven = tag.getBoolean("BatGiven");
        rootUntilGameTime = tag.getLong("RootUntil");
        exhausted = tag.getBoolean("Exhausted");
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
