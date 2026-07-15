package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.api.RoleComponent;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 暴食：进食叠正面效果，达上限再抽到同效果则本局永久；每 tick 重施永久 buff 并清普通负面。
 */
public final class GluttonyComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<GluttonyComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_gluttony"), GluttonyComponent.class);

    /** 0-based amplifier cap (level = amp + 1). At max, another roll of the same effect becomes permanent. */
    public static final int MAX_AMPLIFIER = 4;
    /** Temporary buff duration after eat / stack (2 minutes). */
    public static final int TEMP_DURATION_TICKS = 20 * 120;
    /** Permanent buff refresh window each serverTick. */
    public static final int PERMANENT_REFRESH_TICKS = 40;

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
    /** effect registry id → amplifier + permanent flag for this round */
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
     * Called after a real food consume ({@code Player.eat} RETURN). Rolls one whitelist buff.
     */
    public static void onSuccessfulEat(ServerPlayer sp) {
        if (sp == null || sp.isSpectator()) return;
        if (!isGluttony(sp)) return;
        try {
            KEY.get(sp).rollBuff(sp);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Gluttony] onSuccessfulEat failed for {}",
                    sp.getGameProfile().getName(), t);
        }
    }

    public void rollBuff(ServerPlayer self) {
        if (WHITELIST.isEmpty()) return;
        Holder<MobEffect> picked = WHITELIST.get(ThreadLocalRandom.current().nextInt(WHITELIST.size()));
        ResourceLocation id = effectId(picked);
        if (id == null) return;

        BuffEntry entry = buffs.get(id);
        if (entry == null) {
            entry = new BuffEntry(0, false);
            buffs.put(id, entry);
            applyTemp(self, picked, entry.amplifier);
            self.displayClientMessage(
                    Component.literal("§a[暴食] 获得 " + effectLabel(picked) + " I"),
                    true
            );
            KEY.sync(player);
            return;
        }

        if (entry.permanent) {
            // Already permanent for the round — refresh and notify lightly.
            applyPermanent(self, picked, entry.amplifier);
            self.displayClientMessage(
                    Component.literal("§e[暴食] " + effectLabel(picked) + " 已永久，再次享用。"),
                    true
            );
            return;
        }

        if (entry.amplifier < MAX_AMPLIFIER) {
            entry.amplifier++;
            applyTemp(self, picked, entry.amplifier);
            self.displayClientMessage(
                    Component.literal("§a[暴食] " + effectLabel(picked)
                            + " 升至 " + roman(entry.amplifier + 1)),
                    true
            );
            KEY.sync(player);
            return;
        }

        // At max amp and rolled again → permanent for this round
        entry.permanent = true;
        applyPermanent(self, picked, entry.amplifier);
        self.displayClientMessage(
                Component.literal("§6[暴食] " + effectLabel(picked)
                        + " " + roman(entry.amplifier + 1) + " 已永久化！"),
                true
        );
        KEY.sync(player);
    }

    private void applyTemp(ServerPlayer self, Holder<MobEffect> effect, int amplifier) {
        self.addEffect(new MobEffectInstance(
                effect, TEMP_DURATION_TICKS, amplifier, false, true, true));
    }

    private void applyPermanent(ServerPlayer self, Holder<MobEffect> effect, int amplifier) {
        self.addEffect(new MobEffectInstance(
                effect, PERMANENT_REFRESH_TICKS, amplifier, false, true, true));
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

        reapplyPermanents(self);
        scrubDebuffs(self);
    }

    private void reapplyPermanents(ServerPlayer self) {
        if (buffs.isEmpty()) return;
        for (Map.Entry<ResourceLocation, BuffEntry> e : buffs.entrySet()) {
            if (!e.getValue().permanent) continue;
            Holder<MobEffect> holder = resolveEffect(e.getKey());
            if (holder == null) continue;
            applyPermanent(self, holder, e.getValue().amplifier);
        }
    }

    private void scrubDebuffs(ServerPlayer self) {
        // Allowlist scrub of ordinary vanilla negatives.
        for (Holder<MobEffect> debuff : CLEARABLE_DEBUFFS) {
            if (!self.hasEffect(debuff)) continue;
            if (isDenied(effectId(debuff))) continue;
            self.removeEffect(debuff);
        }

        // Extra safety: if any active effect id matches deny fragments, never strip via broader pass.
        // (CLEARABLE is already vanilla-only; this guards future expansions that iterate actives.)
        List<MobEffectInstance> actives = new ArrayList<>(self.getActiveEffects());
        for (MobEffectInstance inst : actives) {
            Holder<MobEffect> holder = inst.getEffect();
            ResourceLocation id = effectId(holder);
            if (id == null) continue;
            if (isDenied(id)) continue;
            // Only strip if it is one of the ordinary clearable set (path match to be safe).
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
            entry.putBoolean("Permanent", e.getValue().permanent);
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
            int amp = Math.max(0, Math.min(MAX_AMPLIFIER, entry.getInt("Amp")));
            boolean permanent = entry.getBoolean("Permanent");
            buffs.put(id, new BuffEntry(amp, permanent));
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
        public boolean permanent;

        public BuffEntry(int amplifier, boolean permanent) {
            this.amplifier = amplifier;
            this.permanent = permanent;
        }
    }
}
