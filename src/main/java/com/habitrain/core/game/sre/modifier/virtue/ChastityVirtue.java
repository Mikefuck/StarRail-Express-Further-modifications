package com.habitrain.core.game.sre.modifier.virtue;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.modifier.HabiModifiers;
import com.habitrain.core.game.sre.role.sins.SinDeathReasons;
import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import io.wifi.starrailexpress.event.AllowPlayerDeath;
import io.wifi.starrailexpress.event.AllowPlayerDeathWithKiller;
import io.wifi.starrailexpress.game.GameConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.component.WorldModifierComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 贞洁：免疫原版中毒 + 登记毒效果/毒死；不清除槟榔/迷幻/诅咒/职业专属。
 */
public final class ChastityVirtue {
    private ChastityVirtue() {}

    private static boolean registered;

    /** Vanilla / ordinary poison-class effects cleared each tick while chastity is held. */
    public static final List<Holder<MobEffect>> CLEARABLE_POISON = List.of(
            MobEffects.POISON,
            MobEffects.WITHER
    );

    public static void init() {
        if (registered) return;
        registered = true;

        // Server tick scrub via modifier tick consumer.
        if (HabiModifiers.CHASTITY != null) {
            HabiModifiers.CHASTITY.setServerGameTickEvent(ChastityVirtue::serverTick);
        }

        AllowPlayerDeath.EVENT.register((player, deathReason) -> {
            if (!(player instanceof ServerPlayer sp)) return true;
            if (!hasChastity(sp)) return true;
            if (isPoisonDeathReason(deathReason)) {
                sp.setHealth(sp.getMaxHealth());
                HabiTrainCore.LOGGER.debug("[Chastity] blocked poison death for {}",
                        sp.getGameProfile().getName());
                return false;
            }
            return true;
        });

        AllowPlayerDeathWithKiller.EVENT.register((victim, killer, deathReason) -> {
            if (!(victim instanceof ServerPlayer dead)) return true;
            if (!hasChastity(dead)) return true;
            if (isPoisonDeathReason(deathReason)) {
                dead.setHealth(dead.getMaxHealth());
                HabiTrainCore.LOGGER.debug("[Chastity] blocked poison death-with-killer for {}",
                        dead.getGameProfile().getName());
                return false;
            }
            return true;
        });

        HabiTrainCore.LOGGER.info("[ChastityVirtue] poison scrub + death cancel registered");
    }

    public static boolean hasChastity(Player player) {
        if (player == null || HabiModifiers.CHASTITY == null) return false;
        try {
            WorldModifierComponent wmc = WorldModifierComponent.getInstance(player);
            return wmc != null && wmc.isModifier(player, HabiModifiers.CHASTITY);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void serverTick(ServerPlayer sp) {
        if (sp == null || sp.level().isClientSide) return;
        if (!sp.isAlive() || sp.isSpectator()) return;
        if (!hasChastity(sp)) return;
        scrubPoison(sp);
    }

    public static void scrubPoison(ServerPlayer self) {
        for (Holder<MobEffect> poison : CLEARABLE_POISON) {
            if (!self.hasEffect(poison)) continue;
            if (isDenied(effectId(poison))) continue;
            self.removeEffect(poison);
        }

        // Also strip any active effect whose registry path looks like poison/venom/toxic
        // but never betel/psycho/curse fragments (same deny list as gluttony).
        List<MobEffectInstance> actives = new ArrayList<>(self.getActiveEffects());
        for (MobEffectInstance inst : actives) {
            Holder<MobEffect> holder = inst.getEffect();
            ResourceLocation id = effectId(holder);
            if (id == null) continue;
            if (isDenied(id)) continue;
            if (isPoisonEffectId(id) || isClearablePoison(holder)) {
                self.removeEffect(holder);
            }
        }
    }

    private static boolean isClearablePoison(Holder<MobEffect> holder) {
        for (Holder<MobEffect> d : CLEARABLE_POISON) {
            if (d.equals(holder) || d.value() == holder.value()) return true;
        }
        return false;
    }

    private static boolean isPoisonEffectId(ResourceLocation id) {
        String p = id.getPath().toLowerCase(Locale.ROOT);
        return p.contains("poison") || p.contains("venom") || p.contains("toxic") || p.contains("wither");
    }

    public static boolean isPoisonDeathReason(ResourceLocation reason) {
        if (reason == null) return false;
        if (SinDeathReasons.isPoisonDeath(reason)) return true;
        try {
            if (GameConstants.DeathReasons.POISON != null
                    && GameConstants.DeathReasons.POISON.equals(reason)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        String p = reason.getPath().toLowerCase(Locale.ROOT);
        return p.contains("poison") || p.contains("venom") || p.contains("toxic") || p.contains("wither");
    }

    /** Reuse gluttony deny fragments for betel/psycho/curse/custom. */
    public static boolean isDenied(ResourceLocation id) {
        return GluttonyComponent.isDenied(id);
    }

    private static ResourceLocation effectId(Holder<MobEffect> holder) {
        if (holder == null) return null;
        return holder.unwrapKey()
                .map(k -> k.location())
                .orElseGet(() -> BuiltInRegistries.MOB_EFFECT.getKey(holder.value()));
    }
}
