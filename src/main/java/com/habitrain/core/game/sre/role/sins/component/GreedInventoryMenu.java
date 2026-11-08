package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.ShopContent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** A live read-only view whose clicks execute one server-authoritative purchase. */
public final class GreedInventoryMenu extends ChestMenu {
    private final ServerPlayer greed;
    private final ServerPlayer target;
    private final SimpleContainer view;

    private GreedInventoryMenu(int id, ServerPlayer greed, ServerPlayer target) {
        this(id, greed, target, new SimpleContainer(45));
    }

    private GreedInventoryMenu(int id, ServerPlayer greed, ServerPlayer target, SimpleContainer view) {
        super(MenuType.GENERIC_9x5, id, greed.getInventory(), view, 5);
        this.greed = greed;
        this.target = target;
        this.view = view;
        refresh();
    }

    public static boolean open(ServerPlayer greed, ServerPlayer target) {
        if (greed == target || !GreedEconomy.isActive(greed) || !GreedEconomy.isActive(target)
                || greed.level() != target.level() || greed.distanceToSqr(target) > 16
                || !greed.hasLineOfSight(target) || !HabiRoles.isHabiRole(greed, SevenSins.GREED)) return false;
        return greed.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                new GreedInventoryMenu(id, greed, target),
                Component.translatable("menu.habitrain_core.greed.inventory", target.getDisplayName()))).isPresent();
    }

    @Override
    public boolean stillValid(Player player) {
        return player == greed && GreedEconomy.isActive(greed) && GreedEconomy.isActive(target)
                && greed.level() == target.level() && greed.distanceToSqr(target) <= 16
                && greed.hasLineOfSight(target) && HabiRoles.isHabiRole(greed, SevenSins.GREED);
    }

    private void refresh() {
        for (int i = 0; i < target.getInventory().getContainerSize(); i++) {
            view.setItem(i, target.getInventory().getItem(i).copy());
        }
    }

    @Override
    public void broadcastChanges() {
        if (view != null) refresh();
        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player) || slot < 0 || slot >= target.getInventory().getContainerSize()
                || (type != ClickType.PICKUP && type != ClickType.QUICK_MOVE)
                || !getCarried().isEmpty()) return;
        ItemStack actual = target.getInventory().getItem(slot);
        // Reject stale client views, including a slot changed by another thief.
        if (actual.isEmpty() || !ItemStack.matches(actual, view.getItem(slot))) {
            broadcastChanges();
            return;
        }
        int cost = price(actual);
        if (cost < 0) {
            greed.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.unpriced"), true);
            return;
        }
        var wallet = SREPlayerShopComponent.KEY.get(greed);
        if (wallet.balance < cost) {
            int remaining = Math.max(0, wallet.balance);
            wallet.balance = 0;
            wallet.sync();
            if (remaining > 0) {
                GreedEconomy.credit(target, remaining);
                target.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.compensation", remaining), false);
            }
            greed.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.backlash", remaining), false);
            greed.closeContainer();
            return;
        }
        ItemStack taken = actual.copyWithCount(1);
        if (!canReceive(taken)) return;
        wallet.balance -= cost;
        wallet.sync();
        actual.shrink(1);
        target.getInventory().setChanged();
        greed.getInventory().add(taken);
        greed.getInventory().setChanged();
        target.containerMenu.broadcastChanges();
        broadcastChanges();
        greed.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.taken", cost), true);
    }

    private boolean canReceive(ItemStack item) {
        for (ItemStack slot : greed.getInventory().items) {
            if (slot.isEmpty() || (ItemStack.isSameItemSameComponents(slot, item)
                    && slot.getCount() < slot.getMaxStackSize())) return true;
        }
        greed.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.full"), true);
        return false;
    }

    private static int price(ItemStack stack) {
        if (GreedPouchItem.isGreedPouch(stack) || HabiRoleItems.isScapegoatKnife(stack)) return -1;
        int killer = lookup(stack, ShopContent.getDefaultKnifeEntries());
        if (killer >= 0) return killer;
        int civilian = lookup(stack, ShopContent.getShopEntries(TMMRoles.CIVILIAN.getIdentifier()));
        if (civilian >= 0) return civilian;
        int lowest = Integer.MAX_VALUE;
        for (var role : TMMRoles.ROLES.values()) {
            if (!role.isInnocent() || role.canUseKiller()) continue;
            int candidate = lookup(stack, ShopContent.getShopEntries(role.getIdentifier()));
            if (candidate >= 0) lowest = Math.min(lowest, candidate);
        }
        if (lowest != Integer.MAX_VALUE) return lowest;
        for (var role : TMMRoles.ROLES.values()) {
            if (role.isInnocent() && !role.canUseKiller()) continue;
            int candidate = lookup(stack, ShopContent.getShopEntries(role.getIdentifier()));
            if (candidate >= 0) lowest = Math.min(lowest, candidate);
        }
        return lowest == Integer.MAX_VALUE ? -1 : lowest;
    }

    private static int lookup(ItemStack stack, List<ShopEntry> entries) {
        if (entries == null) return -1;
        for (var entry : entries) {
            if (entry != null
                    && entry.currency() == ShopEntry.Currency.MONEY
                    && matchesShopStack(stack, entry.stack())) {
                return GreedPolicy.doubledUnitPrice(entry.price(), entry.stack().getCount());
            }
        }
        return -1;
    }

    private static boolean matchesShopStack(ItemStack targetStack, ItemStack shopStack) {
        if (targetStack == null || shopStack == null || targetStack.isEmpty() || shopStack.isEmpty()) {
            return false;
        }
        if (!targetStack.is(shopStack.getItem())) {
            return false;
        }
        boolean targetSpecial = hasSpecialSkillComponents(targetStack);
        boolean shopSpecial = hasSpecialSkillComponents(shopStack);
        if (targetSpecial || shopSpecial) {
            return ItemStack.isSameItemSameComponents(targetStack, shopStack);
        }
        return true;
    }

    private static boolean hasSpecialSkillComponents(ItemStack stack) {
        return HabiRoleItems.isRedApple(stack)
                || HabiRoleItems.isPepperSpray(stack)
                || HabiRoleItems.isBouquet(stack)
                || HabiRoleItems.isScapegoatKnife(stack)
                || HabiRoleItems.hasFlag(stack, HabiRoleItems.TAG_LUST_INCENSE)
                || stack.has(DataComponents.CUSTOM_DATA)
                || stack.has(DataComponents.CUSTOM_NAME);
    }
}
