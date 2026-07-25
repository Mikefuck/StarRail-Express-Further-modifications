package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREAbilityPlayerComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 暴食：进食叠正面效果；重复升级 amp；每次 +30s，单效果时长上限 60s；
 * 进食加 buff 有 10s CD，用统一技能 HUD 显示。
 */
public final class GluttonyComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<GluttonyComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_gluttony"), GluttonyComponent.class);

    public static final ResourceLocation BUFF_SKILL_ID = HabiTrainCore.id("sin_gluttony_buff");
    public static final int BUFF_CD_SECONDS = 10;
    public static final int BUFF_CD_TICKS = BUFF_CD_SECONDS * 20;

    /** 0-based amplifier cap (level = amp + 1). */
    public static final int LEGACY_MAX_AMPLIFIER = 2;
    /** Duration added on each successful eat of that effect. */
    public static final int BUFF_ADD_TICKS = 20 * 30;
    /** Hard cap on remaining duration for a single effect. */
    public static final int BUFF_MAX_TICKS = 20 * 60;

    /** Positive effects rolled on successful eat. */
    public static final List<Holder<MobEffect>> WHITELIST = List.of(
            MobEffects.MOVEMENT_SPEED,
            MobEffects.DIG_SPEED,
            MobEffects.DAMAGE_BOOST,
            MobEffects.DAMAGE_RESISTANCE,
            MobEffects.JUMP,
            MobEffects.REGENERATION,
            MobEffects.NIGHT_VISION,
            MobEffects.LUCK
    );

    /** Ordinary vanilla debuffs scrubbed each tick while gluttony is active. */
    public static final List<Holder<MobEffect>> CLEARABLE_DEBUFFS = List.of(
            MobEffects.POISON,
            MobEffects.WITHER,
            MobEffects.WEAKNESS,
            MobEffects.MOVEMENT_SLOWDOWN,
            MobEffects.DIG_SLOWDOWN,
            MobEffects.BLINDNESS,
            MobEffects.HUNGER,
            MobEffects.CONFUSION
    );

    /**
     * Registry path fragments that must never be force-cleared
     * (betel / psycho / curse / custom filters / mod effects).
     */
    public static final List<String> NEVER_CLEAR_PATH_FRAGMENTS = List.of(
            "betel",
            "psycho",
            "curse",
            "filter",
            "addiction",
            "withdrawal",
            "habitrain",
            "noelles",
            "starrail",
            "sre",
            "custom"
    );

    private final Player player;
    /** effect registry id → amplifier + remaining ticks for this round */
    private final Map<ResourceLocation, BuffEntry> buffs = new HashMap<>();

    public GluttonyComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public Map<ResourceLocation, BuffEntry> getBuffsView() {
        return Map.copyOf(buffs);
    }

    public static boolean isGluttony(Player target) {
        if (target == null || target.level() == null) return false;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(target.level());
            return game != null
                    && SevenSins.GLUTTONY != null
                    && game.isRole(target, SevenSins.GLUTTONY);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * HUD-only skill: never activates on G press; CD is driven by successful eats.
     */
    public static boolean useBuffSkillHud(RoleSkill.RoleSkillContext ctx) {
        if (ctx != null && ctx.player() != null) {
            ctx.player().displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_gluttony.buff_passive"),
                    true
            );
        }
        return false;
    }

    /**
     * Called after a real food consume ({@code Player.eat} RETURN). Rolls one whitelist buff.
     */
    public static void onSuccessfulEat(ServerPlayer sp) {
        if (sp == null || sp.isSpectator()) return;
        if (!isGluttony(sp)) return;
        try {
            if (isBuffOnCooldown(sp)) {
                sp.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_gluttony.buff_cd"),
                        true
                );
                return;
            }
            KEY.get(sp).rollBuff(sp);
            applyBuffSkillCooldown(sp);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Gluttony] onSuccessfulEat failed for {}",
                    sp.getGameProfile().getName(), t);
        }
    }

    private static boolean isBuffOnCooldown(ServerPlayer sp) {
        try {
            SREAbilityPlayerComponent ability = SREAbilityPlayerComponent.KEY.get(sp);
            if (ability == null) return false;
            // Only check skill cooldown ticks — canUseSkill also blocks during SAFE_TIME.
            return ability.getSkillState(BUFF_SKILL_ID).cooldown > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyBuffSkillCooldown(ServerPlayer sp) {
        try {
            SREAbilityPlayerComponent ability = SREAbilityPlayerComponent.KEY.get(sp);
            if (ability != null) {
                ability.setSkillCooldown(BUFF_SKILL_ID, BUFF_CD_TICKS);
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.debug("[Gluttony] setSkillCooldown failed", t);
        }
    }

    public void rollBuff(ServerPlayer self) {
        if (WHITELIST.isEmpty()) return;
        Holder<MobEffect> picked = WHITELIST.get(ThreadLocalRandom.current().nextInt(WHITELIST.size()));
        ResourceLocation id = effectId(picked);
        if (id == null) return;

        BuffEntry entry = buffs.get(id);
        if (entry == null) {
            entry = new BuffEntry(0, BUFF_ADD_TICKS);
            buffs.put(id, entry);
            apply(self, picked, entry);
            self.displayClientMessage(
                    Component.literal("§a[暴食] 获得 " + effectLabel(picked) + " I（30s）"),
                    true
            );
            KEY.sync(player);
            return;
        }

        int cap = maxAmplifier(picked);
        if (entry.amplifier < cap) {
            entry.amplifier++;
            entry.remainingTicks = Math.min(BUFF_MAX_TICKS, entry.remainingTicks + BUFF_ADD_TICKS);
            apply(self, picked, entry);
            self.displayClientMessage(
                    Component.literal("§a[暴食] " + effectLabel(picked)
                            + " 升至 " + roman(entry.amplifier + 1)
                            + "（" + (entry.remainingTicks / 20) + "s）"),
                    true
            );
            KEY.sync(player);
            return;
        }

        // Already at amp cap: only extend duration (still capped at 60s).
        int before = entry.remainingTicks;
        entry.remainingTicks = Math.min(BUFF_MAX_TICKS, entry.remainingTicks + BUFF_ADD_TICKS);
        apply(self, picked, entry);
        if (entry.remainingTicks > before) {
            self.displayClientMessage(
                    Component.literal("§e[暴食] " + effectLabel(picked) + " 已满级，时长 +"
                            + ((entry.remainingTicks - before) / 20) + "s（上限 60s）"),
                    true
            );
        } else {
            self.displayClientMessage(
                    Component.literal("§e[暴食] " + effectLabel(picked) + " 已满级且时长已达上限。"),
                    true
            );
        }
        KEY.sync(player);
    }

    private void apply(ServerPlayer self, Holder<MobEffect> effect, BuffEntry entry) {
        int duration = Math.max(1, entry.remainingTicks);
        self.addEffect(new MobEffectInstance(
                effect, duration, entry.amplifier, false, true, true));
    }

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        buffs.clear();
    }

    @Override
    public void serverTick() {
        if (player.level().isClientSide) return;
        if (!(player instanceof ServerPlayer self)) return;
        if (self.isSpectator() || !self.isAlive()) return;
        if (!isGluttony(self)) return;

        tickBuffs(self);
        scrubDebuffs(self);
    }

    private void tickBuffs(ServerPlayer self) {
        if (buffs.isEmpty()) return;
        Iterator<Map.Entry<ResourceLocation, BuffEntry>> it = buffs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ResourceLocation, BuffEntry> e = it.next();
            BuffEntry entry = e.getValue();
            entry.remainingTicks--;
            if (entry.remainingTicks <= 0) {
                it.remove();
                Holder<MobEffect> holder = resolveEffect(e.getKey());
                if (holder != null) {
                    self.removeEffect(holder);
                }
                continue;
            }
            Holder<MobEffect> holder = resolveEffect(e.getKey());
            if (holder == null) continue;
            MobEffectInstance active = self.getEffect(holder);
            if (active == null
                    || active.getAmplifier() < entry.amplifier
                    || active.getDuration() < entry.remainingTicks) {
                apply(self, holder, entry);
            }
        }
    }

    private void scrubDebuffs(ServerPlayer self) {
        for (Holder<MobEffect> debuff : CLEARABLE_DEBUFFS) {
            if (!self.hasEffect(debuff)) continue;
            if (isDenied(effectId(debuff))) continue;
            self.removeEffect(debuff);
        }

        List<MobEffectInstance> actives = new ArrayList<>(self.getActiveEffects());
        for (MobEffectInstance inst : actives) {
            Holder<MobEffect> holder = inst.getEffect();
            ResourceLocation id = effectId(holder);
            if (id == null) continue;
            if (isDenied(id)) continue;
            if (isClearableVanilla(holder)) {
                self.removeEffect(holder);
            }
        }
    }

    private static boolean isClearableVanilla(Holder<MobEffect> holder) {
        for (Holder<MobEffect> d : CLEARABLE_DEBUFFS) {
            if (d.equals(holder) || d.value() == holder.value()) return true;
        }
        return false;
    }

    public static boolean isDenied(@Nullable ResourceLocation id) {
        if (id == null) return false;
        String path = id.getPath().toLowerCase(Locale.ROOT);
        String full = id.toString().toLowerCase(Locale.ROOT);
        for (String frag : NEVER_CLEAR_PATH_FRAGMENTS) {
            if (path.contains(frag) || full.contains(frag)) return true;
        }
        return false;
    }

    private static @Nullable ResourceLocation effectId(Holder<MobEffect> holder) {
        if (holder == null) return null;
        return holder.unwrapKey()
                .map(k -> k.location())
                .orElseGet(() -> BuiltInRegistries.MOB_EFFECT.getKey(holder.value()));
    }

    private static @Nullable Holder<MobEffect> resolveEffect(ResourceLocation id) {
        if (id == null) return null;
        return BuiltInRegistries.MOB_EFFECT.getHolder(id).orElse(null);
    }

    private static String effectLabel(Holder<MobEffect> holder) {
        try {
            return Component.translatable(holder.value().getDescriptionId()).getString();
        } catch (Throwable t) {
            ResourceLocation id = effectId(holder);
            return id != null ? id.getPath() : "effect";
        }
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        ListTag list = new ListTag();
        for (Map.Entry<ResourceLocation, BuffEntry> e : buffs.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", e.getKey().toString());
            entry.putInt("Amp", e.getValue().amplifier);
            entry.putInt("Remain", e.getValue().remainingTicks);
            list.add(entry);
        }
        tag.put("Buffs", list);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        buffs.clear();
        if (!tag.contains("Buffs", Tag.TAG_LIST)) return;
        ListTag list = tag.getList("Buffs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String idStr = entry.getString("Id");
            if (idStr == null || idStr.isEmpty()) continue;
            ResourceLocation id = ResourceLocation.tryParse(idStr);
            if (id == null) continue;
            Holder<MobEffect> holder = resolveEffect(id);
            int cap = holder != null ? maxAmplifier(holder) : LEGACY_MAX_AMPLIFIER;
            int amp = Math.max(0, Math.min(cap, entry.getInt("Amp")));
            int remain;
            if (entry.contains("Remain")) {
                remain = Math.max(0, Math.min(BUFF_MAX_TICKS, entry.getInt("Remain")));
            } else {
                // Legacy permanent/temp entries → give one add window.
                remain = BUFF_ADD_TICKS;
            }
            if (remain > 0) {
                buffs.put(id, new BuffEntry(amp, remain));
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

    public static final class BuffEntry {
        public int amplifier;
        public int remainingTicks;

        public BuffEntry(int amplifier, int remainingTicks) {
            this.amplifier = amplifier;
            this.remainingTicks = remainingTicks;
        }
    }

    public static boolean isOrdinaryDebuff(Holder<MobEffect> holder) {
        return holder != null && isClearableVanilla(holder);
    }

    private static int maxAmplifier(Holder<MobEffect> effect) {
        if (effect.equals(MobEffects.NIGHT_VISION)) return 0;
        if (effect.equals(MobEffects.DAMAGE_BOOST)
                || effect.equals(MobEffects.DAMAGE_RESISTANCE)
                || effect.equals(MobEffects.REGENERATION)
                || effect.equals(MobEffects.LUCK)) return 1;
        if (effect.equals(MobEffects.MOVEMENT_SPEED)
                || effect.equals(MobEffects.DIG_SPEED)
                || effect.equals(MobEffects.JUMP)) return 2;
        return LEGACY_MAX_AMPLIFIER;
    }
}
