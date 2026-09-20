package com.habitrain.core.role.change;

import io.wifi.starrailexpress.cca.SREPlayerPsychoComponent;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.agmas.noellesroles.game.roles.neutral.monokuma.MonokumaPlayerComponent;
import org.agmas.noellesroles.game.roles.neutral.panda.PandaComponent;
import org.agmas.noellesroles.init.ModEffects;
import pro.fazeclan.river.stupid_express.constants.SEModifiers;

/** Releases cross-role state only after a role change commits, before assignment events. */
final class SpecialRoleExitCleanup {
    private SpecialRoleExitCleanup() {}

    static void clear(ServerPlayer player) {
        if (player == null) return;
        var monokuma = MonokumaPlayerComponent.KEY.maybeGet(player).orElse(null);
        var panda = PandaComponent.KEY.maybeGet(player).orElse(null);
        int phase = monokuma == null ? 0 : monokuma.phase;
        var modifiers = WorldModifierComponent.KEY.get(player.level());
        boolean blackWhite = SEModifiers.BLACK_WHITE != null
                && modifiers.isModifier(player, SEModifiers.BLACK_WHITE);
        if (phase == 0 && !blackWhite && (panda == null || !panda.isPanda)) return;

        if (blackWhite) modifiers.removeModifier(player, SEModifiers.BLACK_WHITE);
        if (monokuma != null) {
            // Upstream clear(phase=2) unconditionally removes the global effect,
            // even when another player's frenzy is still active. Handle that below.
            monokuma.phase = 0;
            monokuma.clear();
        }
        if (panda != null) panda.clear();
        // An early kill-triggered phase-3 transition can leave phase-2 effects alive.
        if (phase == 2 || phase == 3) {
            removeMatchingEffect(player, ModEffects.NO_COLLIDE, false);
            removeMatchingEffect(player, MobEffects.MOVEMENT_SLOWDOWN, false);
        }
        if (phase == 2) {
            var psycho = SREPlayerPsychoComponent.KEY.get(player);
            psycho.stopPsychoAndRefreshPsychoCount(true);
            psycho.sync();
            boolean anotherFrenzy = player.serverLevel().players().stream()
                    .filter(other -> other != player)
                    .anyMatch(other -> MonokumaPlayerComponent.KEY.maybeGet(other)
                            .map(state -> state.phase == 2).orElse(false));
            if (!anotherFrenzy) {
                for (var other : player.serverLevel().players()) {
                    other.removeEffect(ModEffects.MONOKUMA_FRENZY);
                }
            }
        }
        if (phase == 3) {
            removeMatchingEffect(player, ModEffects.INVINCIBLE, true);
            removeMatchingEffect(player, MobEffects.INVISIBILITY, true);
        }
    }

    private static void removeMatchingEffect(ServerPlayer player, Holder<MobEffect> effect,
                                             boolean permanent) {
        MobEffectInstance instance = player.getEffect(effect);
        if (instance != null && matchesOwnedEffect(instance.getAmplifier(), instance.getDuration(),
                instance.isAmbient(), instance.isVisible(), instance.showIcon(), permanent)) {
            player.removeEffect(effect);
        }
    }

    // Upstream has no effect-owner metadata. Match its exact effect signature;
    // preserve stronger, differently styled, or longer effects from other sources.
    static boolean matchesOwnedEffect(int amplifier, int duration, boolean ambient,
                                      boolean visible, boolean icon, boolean permanent) {
        return amplifier == 0 && !visible && !icon && ambient == permanent
                && (permanent ? duration == -1
                : duration > 0 && duration <= MonokumaPlayerComponent.FRENZY_DURATION);
    }
}
