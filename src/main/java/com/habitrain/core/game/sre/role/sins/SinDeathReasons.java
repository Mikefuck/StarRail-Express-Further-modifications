package com.habitrain.core.game.sre.role.sins;

import net.minecraft.resources.ResourceLocation;
import java.util.Locale;
import java.util.Set;

public final class SinDeathReasons {
    private SinDeathReasons() {}

    /** Conventional weapons pride/wrath/envy may cancel when allowed. */
    private static final Set<String> CONVENTIONAL = Set.of(
            "knife", "bat", "nunchuck", "fist", "revolver", "gun",
            "throwing_knife", "once_revolver", "sheriff_gun"
    );

    private static final Set<String> POISON = Set.of(
            "poison", "wither", "toxic", "venom"
    );

    private static final Set<String> FORCE_ALWAYS = Set.of(
            "fell_out_of_train", "disconnected", "sanity_collapse",
            "exile", "void", "command"
    );

    public static boolean isForcePath(ResourceLocation reason) {
        if (reason == null) return false;
        return FORCE_ALWAYS.contains(reason.getPath().toLowerCase(Locale.ROOT));
    }

    public static boolean isConventionalWeapon(ResourceLocation reason) {
        if (reason == null) return false;
        String p = reason.getPath().toLowerCase(Locale.ROOT);
        if (FORCE_ALWAYS.contains(p)) return false;
        for (String key : CONVENTIONAL) {
            if (p.contains(key)) return true;
        }
        return false;
    }

    /** Bare-hand / fist path — conventional for pride, but must not advance wrath stages. */
    public static boolean isFistPath(ResourceLocation reason) {
        if (reason == null) return true; // no weapon reason → treat as non-weapon for wrath
        String p = reason.getPath().toLowerCase(Locale.ROOT);
        return p.contains("fist");
    }

    public static boolean isPoisonDeath(ResourceLocation reason) {
        if (reason == null) return false;
        String p = reason.getPath().toLowerCase(Locale.ROOT);
        for (String key : POISON) {
            if (p.contains(key)) return true;
        }
        return false;
    }
}
