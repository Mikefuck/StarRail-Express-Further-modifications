package com.habitrain.core.game.sre.role.sins;

import io.wifi.starrailexpress.game.GameConstants;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Exact death-reason classification. No substring matching is allowed here. */
public final class SinDeathReasons {
    private SinDeathReasons() {}

    private static final Set<ResourceLocation> CONVENTIONAL = immutableNonNullSet(
            GameConstants.DeathReasons.GUN_SHOT,
            GameConstants.DeathReasons.KNIFE,
            GameConstants.DeathReasons.REVOLVER,
            GameConstants.DeathReasons.DERRINGER,
            GameConstants.DeathReasons.BAT,
            GameConstants.DeathReasons.ARROW,
            GameConstants.DeathReasons.TRIDENT,
            GameConstants.DeathReasons.SNIPER_RIFLE,
            GameConstants.DeathReasons.NUNCHUCK,
            GameConstants.DeathReasons.NOELLES_ARROW,
            GameConstants.DeathReasons.PUPPETEER_KNIFE,
            GameConstants.DeathReasons.PUPPETEER_GUN,
            GameConstants.DeathReasons.BATON_KILL,
            GameConstants.DeathReasons.FIRE_AXE,
            GameConstants.DeathReasons.NINJA_KNIFE_KILL,
            GameConstants.DeathReasons.NINJA_SHURIKEN_KILL,
            GameConstants.DeathReasons.SHORT_SHOTGUN,
            GameConstants.DeathReasons.THROWING_KNIFE_HIT
    );

    private static final Set<ResourceLocation> FORCE_ALWAYS = immutableNonNullSet(
            GameConstants.DeathReasons.FELL_OUT_OF_TRAIN,
            GameConstants.DeathReasons.DISCONNECT,
            GameConstants.DeathReasons.VOODOO,
            GameConstants.DeathReasons.GOD_COMMAND,
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "greed_lost_pouch"),
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "wrath_exhaustion")
    );

    private static final Set<ResourceLocation> REGISTERED_POISON =
            ConcurrentHashMap.newKeySet();

    static {
        registerPoisonDeathReason(GameConstants.DeathReasons.POISON);
    }

    public static boolean isForcePath(ResourceLocation reason) {
        return reason != null && FORCE_ALWAYS.contains(reason);
    }

    public static boolean isConventionalWeapon(ResourceLocation reason) {
        return reason != null && !FORCE_ALWAYS.contains(reason) && CONVENTIONAL.contains(reason);
    }

    /** Unarmed/general attack is not a valid wrath stage source. */
    public static boolean isFistPath(ResourceLocation reason) {
        return reason == null || GameConstants.DeathReasons.GENERAL_ATTACK.equals(reason);
    }

    public static boolean isPoisonDeath(ResourceLocation reason) {
        return reason != null && REGISTERED_POISON.contains(reason);
    }

    public static void registerPoisonDeathReason(ResourceLocation reason) {
        if (reason != null) REGISTERED_POISON.add(reason);
    }

    private static Set<ResourceLocation> immutableNonNullSet(ResourceLocation... values) {
        Set<ResourceLocation> result = new HashSet<>();
        Collections.addAll(result, values);
        result.remove(null);
        return Set.copyOf(result);
    }
}
