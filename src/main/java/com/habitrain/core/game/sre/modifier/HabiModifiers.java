package com.habitrain.core.game.sre.modifier;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.modifier.virtue.ChastityVirtue;
import com.habitrain.core.game.sre.modifier.virtue.HumilityVirtue;
import com.habitrain.core.game.sre.modifier.virtue.MercyVirtue;
import com.habitrain.core.game.sre.modifier.virtue.TaskTimeVirtues;
import com.habitrain.core.game.sre.modifier.virtue.TemperanceVirtue;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.agmas.harpymodloader.events.ModifierAssigned;
import org.agmas.harpymodloader.modifiers.HMLModifiers;
import org.agmas.harpymodloader.modifiers.SREModifier;

import java.util.HashSet;

/**
 * habitrain_core 美德修饰符注册入口（七美德：六新建 + 上游慷慨关联）+ 效果 init。
 */
public final class HabiModifiers {
    private HabiModifiers() {}

    public static SREModifier HUMILITY;
    public static SREModifier MERCY;
    public static SREModifier PATIENCE;
    public static SREModifier DILIGENCE;
    public static SREModifier TEMPERANCE;
    public static SREModifier CHASTITY;
    /** Upstream noellesroles:generous — linked only, never re-registered. */
    public static SREModifier GENEROUS;

    private static boolean registered;

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;

        HUMILITY = HMLModifiers.registerModifier(new SREModifier(
                HabiTrainCore.id("virtue_humility"), 0xC0C0C0,
                new HashSet<>(), new HashSet<>(), false, false)).setDefaultMax(3);

        // civilianOnly: mercy only rolls for civilians / good-aligned
        MERCY = HMLModifiers.registerModifier(new SREModifier(
                HabiTrainCore.id("virtue_mercy"), 0x88CCFF,
                new HashSet<>(), new HashSet<>(), false, true)).setDefaultMax(2);

        PATIENCE = HMLModifiers.registerModifier(new SREModifier(
                HabiTrainCore.id("virtue_patience"), 0xA0D8A0,
                new HashSet<>(), new HashSet<>(), false, false)).setDefaultMax(3);

        DILIGENCE = HMLModifiers.registerModifier(new SREModifier(
                HabiTrainCore.id("virtue_diligence"), 0xE0B050,
                new HashSet<>(), new HashSet<>(), false, false)).setDefaultMax(3);

        TEMPERANCE = HMLModifiers.registerModifier(new SREModifier(
                HabiTrainCore.id("virtue_temperance"), 0x90B0E0,
                new HashSet<>(), new HashSet<>(), false, false)).setDefaultMax(2);

        CHASTITY = HMLModifiers.registerModifier(new SREModifier(
                HabiTrainCore.id("virtue_chastity"), 0xF0E6D0,
                new HashSet<>(), new HashSet<>(), false, false)).setDefaultMax(2);

        try {
            GENEROUS = org.agmas.noellesroles.role.TraitorAndModifiers.GENEROUS;
        } catch (Throwable t) {
            GENEROUS = null;
            HabiTrainCore.LOGGER.warn("[HabiModifiers] upstream generous missing", t);
        }

        registerVirtueMutex();
        initEffects();
        HabiTrainCore.LOGGER.info(
                "[HabiModifiers] registered 6 virtues; generous link={}",
                GENEROUS != null ? GENEROUS.identifier() : "null");
    }

    /** Wire P4 virtue runtime hooks after modifiers exist. */
    private static void initEffects() {
        HumilityVirtue.init();
        MercyVirtue.init();
        TaskTimeVirtues.init();
        TemperanceVirtue.init();
        ChastityVirtue.init();
    }

    /**
     * One-virtue rule: when any virtue (incl. generous) is assigned, strip other virtues.
     * Also covers patience ↔ diligence hard exclusive pair.
     */
    private static void registerVirtueMutex() {
        ModifierAssigned.EVENT.register((player, mod) -> {
            if (!VirtueGroup.isVirtue(mod)) {
                return;
            }
            WorldModifierComponent wmc = WorldModifierComponent.getInstance(player);
            if (wmc == null) {
                return;
            }
            HashSet<SREModifier> held = wmc.getModifiers(player);
            if (held == null || held.isEmpty()) {
                return;
            }
            for (SREModifier other : new HashSet<>(held)) {
                if (other == mod || !VirtueGroup.isVirtue(other)) {
                    continue;
                }
                // one-virtue rule always removes other virtues (hard exclusive pair is a subset)
                wmc.removeModifier(player, other);
            }
        });
    }
}
