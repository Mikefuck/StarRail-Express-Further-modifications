package com.habitrain.core.game.blackout;

import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 疯狂模式专用商店条目：购买时不发放物品堆，而是调用 SRE 原版的
 * {@code SREPlayerShopComponent.usePsychoMode(player)} 触发狂暴逻辑
 *（30 秒球棒、隐身份、1 层护盾、5 分钟冷却）。
 *
 * 商店图标仍使用 {@code psycho_mode} 物品堆（父类构造时通过
 * {@link BlackoutShopService#createItemStack} 创建），所以 canDisplay/canBuy 沿用父类即可。
 */
public final class BlackoutPsychoModeShopEntry extends BlackoutRoleShopEntry {

    public BlackoutPsychoModeShopEntry(BlackoutShopDefinition definition) {
        super(definition);
    }

    @Override
    public boolean onBuy(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (isPurchaseLocked(serverPlayer)) {
            SubtitleNotifier.sendTop(serverPlayer,
                    Component.literal("§c疯狂模式"),
                    Component.literal("§c本局已购买过疯狂模式。"),
                    60);
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

        // 触发 SRE 原版疯狂模式逻辑（含冷却/护盾/球棒/身份隐藏）
        boolean activated = SREPlayerShopComponent.usePsychoMode(serverPlayer);
        if (!activated) {
            SubtitleNotifier.sendTop(serverPlayer,
                    Component.literal("§c疯狂模式"),
                    Component.literal("§c当前无法进入疯狂模式（冷却中或条件不满足）。"),
                    60);
            return false;
        }

        if (price > 0 && shop != null) {
            shop.addToBalance(-price);
        }

        if (definition.singlePurchasePerRound()) {
            BlackoutShopService.markPurchased(serverPlayer, definition.key());
        }
        return true;
    }
}