package com.habitrain.core.game.blackout;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import com.habitrain.core.util.SubtitleNotifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 停电模式商店服务。
 *
 * 商店按角色能力绑定：canUseKiller()=true 的角色绑定杀手商店，
 * isVigilanteTeam()=true 的角色绑定警长商店。商店接管通过 mixin 在停电模式
 * 激活时注入 SRE 原版商店查询路径。
 */
public final class BlackoutShopService {
    private static final Map<ResourceLocation, List<BlackoutShopDefinition>> ROLE_SHOPS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<UUID, Set<String>>> PURCHASES = new ConcurrentHashMap<>();

    private BlackoutShopService() {
    }

    public static void bootstrapDefaults() {
        // 按角色能力绑定：所有 canUseKiller() 角色用杀手商店，所有 isVigilanteTeam() 角色用警长商店。
        for (SRERole role : TMMRoles.ROLES.values()) {
            ResourceLocation id = role.getIdentifier();
            if (role.canUseKiller()) {
                replaceRoleShop(id, BlackoutShopCatalog.killerShop());
            } else if (role.isVigilanteTeam()) {
                replaceRoleShop(id, BlackoutShopCatalog.sheriffShop());
            }
        }
    }

    public static void replaceRoleShop(ResourceLocation roleId, List<BlackoutShopDefinition> definitions) {
        if (roleId == null) {
            return;
        }
        ROLE_SHOPS.put(roleId, List.copyOf(definitions));
    }

    public static void appendRoleShopItem(ResourceLocation roleId, BlackoutShopDefinition definition) {
        if (roleId == null) {
            return;
        }
        List<BlackoutShopDefinition> definitions = new ArrayList<>(ROLE_SHOPS.getOrDefault(roleId, List.of()));
        definitions.add(definition);
        ROLE_SHOPS.put(roleId, List.copyOf(definitions));
    }

    public static List<BlackoutShopDefinition> getDefinitions(ResourceLocation roleId) {
        return ROLE_SHOPS.getOrDefault(roleId, List.of());
    }

    public static List<io.wifi.starrailexpress.util.ShopEntry> getShopEntries(ResourceLocation roleId) {
        if (roleId == null || TMMRoles.getRole(roleId) == null) {
            return List.of();
        }

        return getDefinitions(roleId).stream()
                .map(definition -> {
                    if (BlackoutShopCatalog.PSYCHO_MODE.key().equals(definition.key())) {
                        return (io.wifi.starrailexpress.util.ShopEntry) new BlackoutPsychoModeShopEntry(definition);
                    }
                    return (io.wifi.starrailexpress.util.ShopEntry) new BlackoutRoleShopEntry(definition);
                })
                .toList();
    }

    public static boolean hasBlackoutShop(ResourceLocation roleId) {
        return roleId != null && !getDefinitions(roleId).isEmpty();
    }

    public static boolean buySheriffRevolver(ServerPlayer player) {
        if (player == null) {
            return false;
        }

        var level = player.serverLevel();
        if (!BlackoutRoleManager.isSheriff(level, player.getUUID())) {
            SubtitleNotifier.sendTop(player,
                    Component.literal("§c权限不足"),
                    Component.literal("§c只有警长可以使用 /habi_api buy_gun。"),
                    60);
            return false;
        }

        if (hasPurchased(player, BlackoutShopCatalog.REVOLVER.key())) {
            SubtitleNotifier.sendTop(player,
                    Component.literal("§c左轮手枪"),
                    Component.literal("§c你在本局已经买过这把左轮了。"),
                    60);
            return false;
        }

        var shop = SREPlayerShopComponent.KEY.get(player);
        if (shop == null || shop.balance < BlackoutShopCatalog.REVOLVER.price()) {
            int balance = shop == null ? 0 : shop.balance;
            SubtitleNotifier.sendTop(player,
                    Component.literal("§c金币不足"),
                    Component.literal("§c需要 " + BlackoutShopCatalog.REVOLVER.price() + "，当前 " + balance + "。"),
                    60);
            return false;
        }

        ItemStack stack = createItemStack(BlackoutShopCatalog.REVOLVER);
        if (stack.isEmpty()) {
            SubtitleNotifier.sendTop(player,
                    Component.literal("§c错误"),
                    Component.literal("§c未找到物品: " + BlackoutShopCatalog.REVOLVER.itemId()),
                    60);
            return false;
        }

        boolean added = player.getInventory().add(stack);
        shop.addToBalance(-BlackoutShopCatalog.REVOLVER.price());
        if (!added) {
            player.drop(stack, false);
        }
        markPurchased(player, BlackoutShopCatalog.REVOLVER.key());

        SubtitleNotifier.sendTop(player,
                Component.literal("§a购买成功"),
                Component.literal("§a你购买了 " + BlackoutShopCatalog.REVOLVER.displayName()
                        + "，花费 " + BlackoutShopCatalog.REVOLVER.price() + " 金币。"),
                60);
        return true;
    }

    public static String getSummary(ResourceLocation roleId) {
        List<BlackoutShopDefinition> definitions = getDefinitions(roleId);
        if (definitions.isEmpty()) {
            return "";
        }

        return definitions.stream()
                .map(BlackoutShopService::formatDefinition)
                .reduce((left, right) -> left + ", " + right)
                .map(summary -> "Shop: " + summary)
                .orElse("");
    }

    public static void resetRound(ServerLevel level) {
        if (level != null) {
            PURCHASES.remove(level.dimension());
        }
    }

    public static boolean hasPurchased(ServerPlayer player, String purchaseKey) {
        return PURCHASES.getOrDefault(player.serverLevel().dimension(), Map.of())
                .getOrDefault(player.getUUID(), Set.of())
                .contains(purchaseKey);
    }

    public static void markPurchased(ServerPlayer player, String purchaseKey) {
        PURCHASES
                .computeIfAbsent(player.serverLevel().dimension(), ignored -> new HashMap<>())
                .computeIfAbsent(player.getUUID(), ignored -> new HashSet<>())
                .add(purchaseKey);
    }

    public static ItemStack createItemStack(BlackoutShopDefinition definition) {
        ResourceLocation location = ResourceLocation.parse(definition.itemId());
        Item item = BuiltInRegistries.ITEM.get(location);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, definition.count());
    }

    private static String formatDefinition(BlackoutShopDefinition definition) {
        String countText = definition.count() > 1 ? " x" + definition.count() : "";
        return definition.displayName() + countText + " (" + definition.price() + ")";
    }
}