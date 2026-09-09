package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerPlayer;

public final class GreedEconomy {
    private GreedEconomy() {}

    public static boolean isActive(ServerPlayer player) {
        var game = SREGameWorldComponent.KEY.get(player.level());
        return game != null && game.isRunning() && game.getRole(player) != null
                && player.isAlive() && !player.hasDisconnected()
                && GameUtils.isPlayerAliveAndSurvival(player);
    }

    public static int interceptIncome(ServerPlayer earner, int oldBalance, int requested) {
        if (requested <= oldBalance || !isActive(earner)) return requested;
        var role = SREGameWorldComponent.KEY.get(earner.level()).getRole(earner);
        if (role.hasNoCoinSystem()) return requested;
        var holders = earner.serverLevel().players().stream()
                .filter(p -> p != earner && isActive(p) && HabiRoles.isHabiRole(p, SevenSins.GREED))
                .toList();
        if (holders.isEmpty()) return requested;
        // One faction share is split between holders; injected duplicate roles cannot multiply the tax.
        boolean innocent = (role.isInnocent() || role.isVigilanteTeam())
                && !role.isNeutrals() && !role.isNeutralForKiller();
        int amount = GreedComponent.KEY.get(earner).tax(requested - oldBalance, innocent);
        for (int i = 0; i < holders.size(); i++) {
            credit(holders.get(i), amount / holders.size() + (i < amount % holders.size() ? 1 : 0));
        }
        return requested - amount;
    }

    // Transfers are not newly earned income and must bypass the income interceptor.
    public static void credit(ServerPlayer player, int amount) {
        var shop = SREPlayerShopComponent.KEY.get(player);
        shop.balance = (int) Math.min(Integer.MAX_VALUE, (long) shop.balance + amount);
        shop.sync();
    }

    public static void distributeEstate(ServerPlayer greed) {
        if (!GreedComponent.KEY.get(greed).claimEstate()) return;
        var shop = SREPlayerShopComponent.KEY.get(greed);
        int balance = Math.max(0, shop.balance);
        shop.balance = 0;
        shop.sync();
        var survivors = greed.serverLevel().players().stream()
                .filter(p -> p != greed && isActive(p)).toList();
        for (int i = 0; i < survivors.size(); i++) {
            credit(survivors.get(i), GreedPolicy.estateShare(balance, survivors.size(), i));
        }
    }
}
