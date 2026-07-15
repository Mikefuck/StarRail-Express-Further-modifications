package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import java.util.Set;

public final class SevenSins {
    private SevenSins() {}

    public static final ResourceLocation PRIDE_ID = HabiTrainCore.id("sin_pride");
    public static final ResourceLocation ENVY_ID = HabiTrainCore.id("sin_envy");
    public static final ResourceLocation WRATH_ID = HabiTrainCore.id("sin_wrath");
    public static final ResourceLocation GREED_ID = HabiTrainCore.id("sin_greed");
    public static final ResourceLocation GLUTTONY_ID = HabiTrainCore.id("sin_gluttony");
    public static final ResourceLocation LUST_ID = HabiTrainCore.id("sin_lust");
    public static final ResourceLocation SLOTH_ID = HabiTrainCore.id("sin_sloth");

    public static SRERole PRIDE, ENVY, WRATH, GREED, GLUTTONY, LUST, SLOTH;

    public static Set<ResourceLocation> allIds() {
        return Set.of(PRIDE_ID, ENVY_ID, WRATH_ID, GREED_ID, GLUTTONY_ID, LUST_ID, SLOTH_ID);
    }

    public static boolean isSin(SRERole role) {
        return role != null && allIds().contains(role.getIdentifier());
    }

    public static boolean isIndependentSin(SRERole role) {
        if (role == null) return false;
        ResourceLocation id = role.getIdentifier();
        return PRIDE_ID.equals(id) || GREED_ID.equals(id)
                || LUST_ID.equals(id) || SLOTH_ID.equals(id);
    }

    public static boolean isKillerShareSin(SRERole role) {
        return role != null && WRATH_ID.equals(role.getIdentifier());
    }

    public static void init() {
        // filled in Task 2
    }
}
