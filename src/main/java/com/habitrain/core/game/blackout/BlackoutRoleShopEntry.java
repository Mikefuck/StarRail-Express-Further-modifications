package com.habitrain.core.game.blackout;

import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class BlackoutRoleShopEntry extends ShopEntry {
    protected final BlackoutShopDefinition definition;

    public BlackoutRoleShopEntry(BlackoutShopDefinition definition) {
        super(BlackoutShopService.createItemStack(definition), definition.price(), definition.type());
        this.definition = definition;
    }

    @Override
    public boolean canDisplay(Player player) {
        return !stack().isEmpty() && !isPurchaseLocked(player);
    }

    @Override
    public boolean canBuy(Player player) {
        return !stack().isEmpty() && !isPurchaseLocked(player);
    }

    @Override
    public boolean onBuy(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (isPurchaseLocked(serverPlayer)) {
            return false;
        }

        int price = definition.price();
        SREPlayerShopComponent shop = SREPlayerShopComponent.KEY.get(serverPlayer);
        if (price > 0 && (shop == null || shop.balance < price)) {
            int balance = shop == null ? 0 : shop.balance;
            SubtitleNotifier.sendTop(serverPlayer,
                    Component.literal("§c金币不足"),
                    Component.literal("§c需要 " + price + "，当前 " + balance + "。"),
                    60);
            return false;
        }

        ItemStack purchased = BlackoutShopService.createItemStack(definition);
        if (purchased.isEmpty()) {
            return false;
        }

        boolean added = serverPlayer.getInventory().add(purchased);
        if (!added) {
            serverPlayer.drop(purchased, false);
        }

        if (price > 0 && shop != null) {
            shop.addToBalance(-price);
        }

        if (definition.singlePurchasePerRound()) {
            BlackoutShopService.markPurchased(serverPlayer, definition.key());
        }
        return true;
    }

    protected boolean isPurchaseLocked(Player player) {
        return definition.singlePurchasePerRound()
                && player instanceof ServerPlayer serverPlayer
                && BlackoutShopService.hasPurchased(serverPlayer, definition.key());
    }
}
