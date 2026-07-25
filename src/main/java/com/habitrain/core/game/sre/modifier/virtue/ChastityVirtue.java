package com.habitrain.core.game.sre.modifier.virtue;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.modifier.HabiModifiers;
import com.habitrain.core.game.sre.role.sins.SinDeathReasons;
import io.wifi.starrailexpress.event.AllowPlayerDeath;
import io.wifi.starrailexpress.event.AllowPlayerDeathWithKiller;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.component.WorldModifierComponent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Chastity only blocks explicitly registered poison effects and death reasons. */
public final class ChastityVirtue {
    private ChastityVirtue() {}

    private static boolean registered;
    private static final Set<ResourceLocation> POISON_EFFECTS = ConcurrentHashMap.newKeySet();

    static {
        registerPoisonEffect(effectId(MobEffects.POISON));
        registerPoisonEffect(effectId(MobEffects.WITHER));
    }

    public static void init() {
        if (registered) return;
        registered = true;
        if (HabiModifiers.CHASTITY != null) {
            HabiModifiers.CHASTITY.setServerGameTickEvent(ChastityVirtue::serverTick);
        }

        AllowPlayerDeath.EVENT.register((player, reason) ->
                !(player instanceof ServerPlayer sp)
                        || !hasChastity(sp)
                        || !blockPoisonDeath(sp, reason));
        AllowPlayerDeathWithKiller.EVENT.register((victim, killer, reason) ->
                !(victim instanceof ServerPlayer sp)
                        || !hasChastity(sp)
                        || !blockPoisonDeath(sp, reason));
        HabiTrainCore.LOGGER.info("[ChastityVirtue] explicit poison registry enabled");
    }

    public static boolean hasChastity(Player player) {
        if (player == null || HabiModifiers.CHASTITY == null) return false;
        try {
            WorldModifierComponent modifiers = WorldModifierComponent.getInstance(player);
            return modifiers != null && modifiers.isModifier(player, HabiModifiers.CHASTITY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void registerPoisonEffect(ResourceLocation effectId) {
        if (effectId != null) POISON_EFFECTS.add(effectId);
    }

    public static void registerPoisonDeathReason(ResourceLocation reason) {
        SinDeathReasons.registerPoisonDeathReason(reason);
    }

    public static boolean isRegisteredPoison(Holder<MobEffect> effect) {
        return effect != null && POISON_EFFECTS.contains(effectId(effect));
    }

    private static void serverTick(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.isSpectator() || !hasChastity(player)) return;
        for (ResourceLocation id : POISON_EFFECTS) {
            Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(id).orElse(null);
            if (effect != null && player.hasEffect(effect)) player.removeEffect(effect);
        }
    }

    private static boolean blockPoisonDeath(ServerPlayer player, ResourceLocation reason) {
        if (!SinDeathReasons.isPoisonDeath(reason)) return false;
        player.setHealth(player.getMaxHealth());
        HabiTrainCore.LOGGER.debug("[Chastity] blocked poison death for {}",
                player.getGameProfile().getName());
        return true;
    }

    private static ResourceLocation effectId(Holder<MobEffect> effect) {
        if (effect == null) return null;
        return effect.unwrapKey().map(key -> key.location())
                .orElseGet(() -> BuiltInRegistries.MOB_EFFECT.getKey(effect.value()));
    }
}
