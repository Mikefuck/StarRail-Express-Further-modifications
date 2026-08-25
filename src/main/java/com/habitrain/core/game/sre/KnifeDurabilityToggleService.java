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
                    && !ConfigManager.getInstance().isKnifeDurabilityEnabled()
                    && isMatchRunning(server)) {
                applyToServer(server);
            }
        });
    }

    /** 1Hz strip only while an SRE/overworld match is running — not in lobby. */
    static boolean isMatchRunning(@Nullable MinecraftServer server) {
        if (server == null) {
            return false;
        }
        try {
            for (var world : server.getAllLevels()) {
                if (world == null) {
                    continue;
                }
                var game = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(world);
                if (game != null && game.isRunning()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void applyToServer(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        boolean enabled = ConfigManager.getInstance().isKnifeDurabilityEnabled();
        for (var player : server.getPlayerList().getPlayers()) {
            if (!isInRunningSreGame(player)) {
                continue;
            }
            applyToPlayer(player, enabled);
        }
    }

    public static void applyToPlayer(net.minecraft.server.level.ServerPlayer player) {
        if (player != null) {
            applyToPlayer(player, ConfigManager.getInstance().isKnifeDurabilityEnabled());
        }
    }

    /** Periodic / config-edge scans must not rewrite lobby knives. */
    private static boolean isInRunningSreGame(net.minecraft.server.level.ServerPlayer player) {
        if (player == null || player.serverLevel() == null) {
            return false;
        }
        try {
            var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(player.serverLevel());
            return gw != null && gw.isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyToPlayer(
            net.minecraft.server.level.ServerPlayer player,
            boolean enabled) {
        var inventory = player.getInventory();
        boolean changed = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(TMMItems.KNIFE)) {
                continue;
            }
            if (enabled) {
                if (!stack.has(DataComponents.MAX_DAMAGE)) {
                    KillerKnifeDurability.applyFreshDurability(stack);
                    changed = true;
                }
            } else if (stack.has(DataComponents.DAMAGE) || stack.has(DataComponents.MAX_DAMAGE)) {
                removeDurability(stack);
                changed = true;
            }
        }
        if (changed) {
            inventory.setChanged();
            player.containerMenu.broadcastChanges();
        }
    }

    public static void removeDurability(ItemStack stack) {
        stack.remove(DataComponents.DAMAGE);
        stack.remove(DataComponents.MAX_DAMAGE);
    }
}
