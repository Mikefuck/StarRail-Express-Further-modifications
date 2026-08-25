package com.habitrain.core.game.sre;

import net.minecraft.world.entity.player.Player;

/**
 * Public face of {@link PerPlayerTaskTicker#canTickCustomTasks(Player)} for
 * completion paths that do not go through the ticker (eat mixin, look / be-alone
 * onTick). Predicate is unchanged: SRE ACTIVE, alive, not spectator/creative,
 * not rest-area.
 */
public final class CustomTaskTickGate {
    private CustomTaskTickGate() {
    }

    public static boolean allow(Player player) {
        return PerPlayerTaskTicker.canTickCustomTasks(player);
    }
}
