package com.habitrain.core.game.sre.modifier.virtue;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.modifier.HabiModifiers;
import io.wifi.starrailexpress.cca.DynamicShopComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.agmas.harpymodloader.events.ModifierRemoved;

/**
 * 节制：同 item 重复购买价 = max(原价×50%, 上次×90%)。
 * 叠价顺序：基础 → 节制 → DynamicShop。
 * 通过 {@link com.habitrain.core.game.sre.mixin.TemperanceShopMixin} 改写 tryBuy 扣费。
 */
public final class TemperanceVirtue {
    public static final double FLOOR_OF_BASE = 0.5;
    public static final double DECAY_OF_LAST = 0.9;

    private TemperanceVirtue() {}

    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;

        ModifierRemoved.EVENT.register((player, mod) -> {
            if (mod == HabiModifiers.TEMPERANCE && player != null) {
                TemperancePurchaseState.clearPlayer(player.getUUID());
            }
        });

        HabiTrainCore.LOGGER.info("[TemperanceVirtue] ModifierRemoved clear + shop mixin quote ready");
    }

    public static boolean hasTemperance(Player player) {
        if (player == null || HabiModifiers.TEMPERANCE == null) return false;
        try {
            WorldModifierComponent wmc = WorldModifierComponent.getInstance(player);
            return wmc != null && wmc.isModifier(player, HabiModifiers.TEMPERANCE);
        } catch (Throwable t) {
            return false;
        }
    }

    public static ResourceLocation itemIdOf(ShopEntry entry) {
        if (entry == null) return null;
        ItemStack stack = entry.stack();
        if (stack == null || stack.isEmpty()) return null;
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    /**
     * Quote after temperance decay, before DynamicShop.
     * first buy: base; later: max(base*0.5, last*0.9).
     */
    public static int temperanceQuote(Player player, ResourceLocation itemId, int basePrice) {
        if (basePrice < 0) return basePrice;
        if (player == null || itemId == null || !hasTemperance(player)) return basePrice;
        Integer last = TemperancePurchaseState.getLastPrice(player.getUUID(), itemId);
        if (last == null) return basePrice;
        int floor = (int) Math.floor(basePrice * FLOOR_OF_BASE);
        int decayed = (int) Math.floor(last * DECAY_OF_LAST);
        return Math.max(floor, decayed);
    }

    /**
     * Full stack: base → temperance → DynamicShop.effectivePrice(id, tempered).
     */
    public static int effectiveBuyPrice(Player player, ShopEntry entry) {
        if (entry == null) return 0;
        int base = entry.price();
        ResourceLocation id = itemIdOf(entry);
        int tempered = temperanceQuote(player, id, base);
        try {
            DynamicShopComponent dyn = DynamicShopComponent.KEY.get(player);
            if (dyn != null && id != null) {
                return dyn.effectivePrice(id, tempered);
            }
        } catch (Throwable ignored) {
        }
        return tempered;
    }

    /**
     * After a successful purchase, remember the charged price for next decay.
     */
    public static void onSuccessfulBuy(Player player, ShopEntry entry, int pricePaid) {
        if (player == null || entry == null || !hasTemperance(player)) return;
        ResourceLocation id = itemIdOf(entry);
        if (id == null) return;
        TemperancePurchaseState.recordPurchase(player.getUUID(), id, pricePaid);
        if (player instanceof ServerPlayer sp) {
            HabiTrainCore.LOGGER.debug("[Temperance] {} bought {} for {} (next decay base)",
                    sp.getGameProfile().getName(), id, pricePaid);
        }
    }

    /** Clear all state (game end). */
    public static void clearAll() {
        TemperancePurchaseState.clearAll();
    }

    /**
     * Helper for mixins that need the shop component player field.
     */
    public static int quoteFromShop(SREPlayerShopComponent shop, ShopEntry entry) {
        if (shop == null || entry == null) return entry == null ? 0 : entry.price();
        return effectiveBuyPrice(shop.getPlayer(), entry);
    }
}
