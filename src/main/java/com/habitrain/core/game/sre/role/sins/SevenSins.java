package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.component.EnvyComponent;
import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import com.habitrain.core.game.sre.role.sins.component.GreedComponent;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.game.sre.role.sins.component.PrideComponent;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import com.habitrain.core.game.sre.role.sins.shop.SevenSinShops;
import com.habitrain.core.game.sre.role.sins.win.GreedRole;
import com.habitrain.core.game.sre.role.sins.win.LustRole;
import com.habitrain.core.game.sre.role.sins.win.PrideRole;
import com.habitrain.core.game.sre.role.sins.win.SlothRole;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.awt.Color;
import java.util.List;
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
        registerRoles();
        registerSkills();
        wireOpposingClique();
        SevenSinsMutex.init();
        com.habitrain.core.game.sre.role.sins.win.SinVictoryHooks.init();
        SevenSinEvents.init();
        HabiTrainCore.LOGGER.info(
                "[SevenSins] registered 7 sins: pride, envy, wrath, greed, gluttony, lust, sloth");
    }

    private static void registerSkills() {
        if (PRIDE != null) {
            RoleSkill.register(PRIDE,
                    RoleSkill.skill(
                            HabiTrainCore.id("sin_pride_copy_shop"),
                            "skill.habitrain_core.sin_pride.copy_shop",
                            PrideComponent::useCopyShop
                    ).cooldownSeconds(PrideComponent.COPY_SHOP_CD_SECONDS)
                            .showOnHud(true)
                            .announceToSelf(true)
                            .build()
            );
        }
        if (ENVY != null) {
            RoleSkill.register(ENVY,
                    RoleSkill.skill(
                            HabiTrainCore.id("sin_envy_mark"),
                            "skill.habitrain_core.sin_envy.mark",
                            EnvyComponent::useMark
                    ).cooldownSeconds(EnvyComponent.MARK_CD_SECONDS)
                            .showOnHud(true)
                            .announceToSelf(true)
                            .build()
            );
        }
    }

    private static void registerRoles() {
        // Pride: independent neutral, instinct, no time
        PRIDE = TMMRoles.registerRole(new PrideRole(
                PRIDE_ID, new Color(180, 40, 40).getRGB(),
                false, false, SRERole.MoodType.REAL,
                TMMRoles.CIVILIAN.getMaxSprintTime(), false
        ).setComponentKey(PrideComponent.KEY)
                .setNeutrals(true)
                .setCanUseInstinct(true)
                .setCanSeeCoin(true)
                .setDefaultMax(1));

        // Envy: killer
        ENVY = TMMRoles.registerRole(new NormalRole(
                ENVY_ID, new Color(40, 160, 60).getRGB(),
                false, true, SRERole.MoodType.FAKE, Integer.MAX_VALUE, false
        ) {
            @Override
            public List<ShopEntry> getShopEntries() {
                return SevenSinShops.envyShop();
            }
        }.setComponentKey(EnvyComponent.KEY)
                .setCanSeeCoin(true)
                .setDefaultMax(1));

        // Wrath: neutral for killer, see time, no instinct; no shop; fake knife/gun start
        WRATH = TMMRoles.registerRole(new NormalRole(
                WRATH_ID, new Color(200, 30, 30).getRGB(),
                false, false, SRERole.MoodType.FAKE,
                Integer.MAX_VALUE, true
        ) {
            @Override
            public List<ItemStack> getDefaultItems() {
                return WrathComponent.defaultItems();
            }

            @Override
            public List<ShopEntry> getShopEntries() {
                return SevenSinShops.empty();
            }
        }.setComponentKey(WrathComponent.KEY)
                .setNeutrals(true)
                .setNeutralForKiller(true)
                .setCanSeeCoin(true)
                .setDefaultMax(1));

        // Greed: independent neutral CustomWinner
        GREED = TMMRoles.registerRole(new GreedRole(
                GREED_ID, new Color(200, 160, 20).getRGB(),
                false, false, SRERole.MoodType.REAL,
                TMMRoles.CIVILIAN.getMaxSprintTime(), false
        ).setComponentKey(GreedComponent.KEY)
                .setNeutrals(true)
                .setCanSeeCoin(true)
                .setDefaultMax(1));

        // Gluttony: innocent civilian
        GLUTTONY = TMMRoles.registerRole(new NormalRole(
                GLUTTONY_ID, new Color(140, 90, 50).getRGB(),
                true, false, SRERole.MoodType.REAL,
                TMMRoles.CIVILIAN.getMaxSprintTime(), false
        ) {
            @Override
            public List<ShopEntry> getShopEntries() {
                return SevenSinShops.gluttonyShop();
            }
        }.setComponentKey(GluttonyComponent.KEY)
                .setCanSeeCoin(true)
                .setDefaultMax(1));

        // Lust: independent neutral, instinct + see time
        LUST = TMMRoles.registerRole(new LustRole(
                LUST_ID, new Color(200, 50, 150).getRGB(),
                false, false, SRERole.MoodType.REAL,
                TMMRoles.CIVILIAN.getMaxSprintTime(), true
        ).setComponentKey(LustComponent.KEY)
                .setNeutrals(true)
                .setCanUseInstinct(true)
                .setCanSeeCoin(true)
                .setDefaultMax(1));

        // Sloth: independent neutral CustomWinner
        SLOTH = TMMRoles.registerRole(new SlothRole(
                SLOTH_ID, new Color(100, 100, 140).getRGB(),
                false, false, SRERole.MoodType.REAL,
                TMMRoles.CIVILIAN.getMaxSprintTime(), false
        ).setComponentKey(SlothComponent.KEY)
                .setNeutrals(true)
                .setCanSeeCoin(true)
                .setDefaultMax(1));
    }

    private static void wireOpposingClique() {
        SRERole[] sins = {PRIDE, ENVY, WRATH, GREED, GLUTTONY, LUST, SLOTH};
        for (int i = 0; i < sins.length; i++) {
            for (int j = i + 1; j < sins.length; j++) {
                sins[i].addTwoWayOpposingRole(sins[j]);
            }
        }
    }
}
