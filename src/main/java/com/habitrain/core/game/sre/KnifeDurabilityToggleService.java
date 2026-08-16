package com.habitrain.core.game.sre;

import com.habitrain.core.config.ConfigManager;
import io.wifi.starrailexpress.game.KillerKnifeDurability;
import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Applies the reversible knife-durability setting to knives already in online inventories. */
public final class KnifeDurabilityToggleService {
    private static boolean registered;

    private KnifeDurabilityToggleService() {}

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0
                    && !ConfigManager.getInstance().isKnifeDurabilityEnabled()) {
                applyToServer(server);
            }
        });
    }

    public static void applyToServer(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        boolean enabled = ConfigManager.getInstance().isKnifeDurabilityEnabled();
        for (var player : server.getPlayerList().getPlayers()) {
            applyToPlayer(player, enabled);
        }
    }

    public static void applyToPlayer(net.minecraft.server.level.ServerPlayer player) {
        if (player != null) {
            applyToPlayer(player, ConfigManager.getInstance().isKnifeDurabilityEnabled());
        }
    }

    private static void applyToPlayer(
            net.minecraft.server.level.ServerPlayer player,
            boolean enabled) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(TMMItems.KNIFE)) {
                continue;
            }
            if (enabled) {
                KillerKnifeDurability.applyFreshDurability(stack);
            } else {
                removeDurability(stack);
            }
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
    }

    public static void removeDurability(ItemStack stack) {
        stack.remove(DataComponents.DAMAGE);
        stack.remove(DataComponents.MAX_DAMAGE);
    }
}
