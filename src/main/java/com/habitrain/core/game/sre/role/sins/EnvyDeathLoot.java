package com.habitrain.core.game.sre.role.sins;

import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.agmas.noellesroles.init.ModItems;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Keeps death-drop weapons available to Envy without consuming the victim's normal drops. */
public final class EnvyDeathLoot {
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private EnvyDeathLoot() {}

    public static boolean preservesDrop(ItemStack stack) {
        return stack.is(TMMItems.REVOLVER) || stack.is(ModItems.SCARLET_PERCEPTION_SWORD);
    }

    /** No reward is granted here: only the confirmed-kill hook may consume this snapshot. */
    public static void duringKill(Player victim, Runnable action) {
        Snapshot previous = CURRENT.get();
        CURRENT.remove();
        ItemStack savedGreedPouch = ItemStack.EMPTY;
        int savedGreedPouchSlot = -1;
        try {
            // Upstream may resolve a different true killer after entering killPlayer.
            // Eligibility belongs to the managed Envy onKill hook, not the incoming killer argument.
            if (victim instanceof ServerPlayer dead) {
                List<ItemStack> weapons = new ArrayList<>();
                for (int slot = 0; slot < dead.getInventory().getContainerSize(); slot++) {
                    ItemStack stack = dead.getInventory().getItem(slot);
                    if (!stack.isEmpty() && preservesDrop(stack)) weapons.add(stack.copyWithCount(1));
                    if (savedGreedPouch.isEmpty()
                            && com.habitrain.core.game.sre.role.sins.item.GreedPouchItem.isBoundPouchOf(dead, stack)) {
                        savedGreedPouch = stack.copy();
                        savedGreedPouchSlot = slot;
                    }
                }
                CURRENT.set(new Snapshot(dead, weapons));
            }
            action.run();
        } finally {
            if (victim instanceof ServerPlayer dead && !savedGreedPouch.isEmpty()) {
                com.habitrain.core.game.sre.role.sins.component.GreedComponent
                        .ensureSingleBoundPouch(dead, savedGreedPouch, savedGreedPouchSlot);
            }
            // Chained kills get their own snapshot; failed attacks cannot leak loot to later kills.
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static @Nullable List<ItemStack> weaponsFor(ServerPlayer victim) {
        Snapshot snapshot = CURRENT.get();
        return snapshot != null && snapshot.victim == victim
                ? snapshot.weapons : null;
    }

    private record Snapshot(ServerPlayer victim, List<ItemStack> weapons) {}
}
