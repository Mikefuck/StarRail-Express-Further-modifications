package com.habitrain.core.game.sre.role.sins.item;

import com.habitrain.core.game.sre.role.HabiRoleItems;
import io.wifi.starrailexpress.index.SREDataComponentTypes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 贪婪绑定收纳袋：原版 {@link Items#BUNDLE} + CUSTOM_DATA 标记 + {@link SREDataComponentTypes#OWNER}。
 * <p>
 * 不注册新 Item；收集通过「袋 + 另一只手物品」右键吸收种类（见 {@code GreedComponent}）。
 */
public final class GreedPouchItem {
    public static final int CAPACITY = 32;
    public static final String TAG_GREED_POUCH = "habitrain_greed_pouch";
    public static final String TAG_GREED_OWNER = "habitrain_greed_owner";
    public static final String TAG_GREED_CONTENTS = "habitrain_greed_contents";
    /** Marks an item transferred by the upstream Thief's "steal" skill. */
    public static final String TAG_STOLEN_BY_THIEF = "habitrain_stolen_by_thief";

    private GreedPouchItem() {}

    public static ItemStack createBoundPouch(Player owner) {
        ItemStack stack = new ItemStack(Items.BUNDLE, 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("贪婪收纳袋"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("绑定：不可丢弃；种类数达到开局人数的三分之一减一获胜（向上取整，至少1种）"),
                Component.literal("把偷来的物品放进袋内即计入种类"),
                Component.literal("容量：32件，每件物品占1格，与堆叠上限无关"),
                Component.literal("也可：主/副手持袋，另一手持物右键吸收")
        )));
        HabiRoleItems.putFlag(stack, TAG_GREED_POUCH, true);
        if (owner != null) {
            String id = owner.getUUID().toString();
            try {
                stack.set(SREDataComponentTypes.OWNER, id);
            } catch (Throwable ignored) {
            }
            CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            tag.putBoolean(TAG_GREED_POUCH, true);
            tag.putString(TAG_GREED_OWNER, id);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return stack;
    }

    public static boolean isGreedPouch(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (HabiRoleItems.hasFlag(stack, TAG_GREED_POUCH)) return true;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().getBoolean(TAG_GREED_POUCH)) return true;
        return false;
    }

    /** Shared by hand absorption, vanilla bundle clicks and collection accounting. */
    public static boolean canStore(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || isGreedPouch(stack)) return false;
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        // Explicit exclusions also apply to items carrying the thief provenance marker.
        if (itemId.equals("supplementaries:bubble_blower") || itemId.equals("supplementaries:flute")) return false;
        if (isStolenByThief(stack)) return true;
        var item = stack.getItem();
        if (item instanceof io.wifi.starrailexpress.content.item.KeyItem
                || item instanceof io.wifi.starrailexpress.content.item.NoteItem
                || stack.is(Items.PAPER)) return false;
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
        // Also cover the Wathe bridge's plain Item letter/key registrations.
        if (path.equals("key") || path.endsWith("_key") || path.equals("letter")
                || path.equals("note")) return false;
        return !stack.has(DataComponents.FOOD)
                && stack.getUseAnimation() != net.minecraft.world.item.UseAnim.EAT
                && stack.getUseAnimation() != net.minecraft.world.item.UseAnim.DRINK;
    }

    public static boolean isStolenByThief(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(TAG_STOLEN_BY_THIEF);
    }

    public static void markStolenByThief(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putBoolean(TAG_STOLEN_BY_THIEF, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static @Nullable UUID getOwnerUuid(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            if (stack.has(SREDataComponentTypes.OWNER)) {
                String owner = stack.get(SREDataComponentTypes.OWNER);
                UUID parsed = parseUuid(owner);
                if (parsed != null) return parsed;
            }
        } catch (Throwable ignored) {
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            if (tag.contains(TAG_GREED_OWNER)) {
                return parseUuid(tag.getString(TAG_GREED_OWNER));
            }
        }
        return null;
    }

    public static boolean isBoundPouchOf(@Nullable Player player, @Nullable ItemStack stack) {
        if (player == null || !isGreedPouch(stack)) return false;
        UUID owner = getOwnerUuid(stack);
        return owner != null && owner.equals(player.getUUID());
    }

    public static boolean playerHasOwnPouch(@Nullable Player player) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isBoundPouchOf(player, player.getInventory().getItem(i))) {
                return true;
            }
        }
        // Cursor during inventory open
        try {
            ItemStack cursor = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
            if (isBoundPouchOf(player, cursor)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Mirrors server-side collection onto the physical bound item.
     * Writes both CUSTOM_DATA backup and vanilla {@link DataComponents#BUNDLE_CONTENTS}
     * so inventory UI and win counting stay aligned.
     */
    public static void setStoredItems(ItemStack pouch, List<ItemStack> contents,
                                      HolderLookup.Provider registries) {
        if (!isGreedPouch(pouch) || registries == null) return;
        CompoundTag tag = pouch.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ListTag list = new ListTag();
        List<ItemStack> visible = new ArrayList<>();
        if (contents != null) {
            for (ItemStack stack : contents) {
                if (stack == null || stack.isEmpty() || isGreedPouch(stack)) continue;
                ItemStack one = stack.copyWithCount(1);
                list.add(one.save(registries));
                if (visible.size() < CAPACITY) visible.add(one.copy());
            }
        }
        tag.put(TAG_GREED_CONTENTS, list);
        pouch.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        pouch.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(visible));
    }

    /**
     * 读取完整收藏账本。CUSTOM_DATA 是权威全集；原版 Bundle 只是在容量限制内的可视子集。
     * Bundle 中由玩家新放入的类型会被合并，但绝不能反向删掉账本中因容量不足未展示的类型。
     */
    public static List<ItemStack> getStoredItems(ItemStack pouch, HolderLookup.Provider registries) {
        if (!isGreedPouch(pouch)) return List.of();
        Map<String, ItemStack> byItemId = new LinkedHashMap<>();
        getLedgerItems(pouch, registries).forEach(stack -> addByItemId(byItemId, stack));
        getBundleItems(pouch).forEach(stack -> addByItemId(byItemId, stack));
        return List.copyOf(byItemId.values());
    }

    /** Read only the complete CUSTOM_DATA ledger, including entries hidden by bundle capacity. */
    public static List<ItemStack> getLedgerItems(ItemStack pouch, HolderLookup.Provider registries) {
        if (!isGreedPouch(pouch) || registries == null) return List.of();
        Map<String, ItemStack> byItemId = new LinkedHashMap<>();
        CustomData data = pouch.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            ListTag list = data.copyTag().getList(TAG_GREED_CONTENTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                ItemStack parsed = ItemStack.parseOptional(registries, list.getCompound(i));
                addByItemId(byItemId, parsed);
            }
        }
        return List.copyOf(byItemId.values());
    }

    /** Read only the items currently visible in the vanilla bundle component. */
    public static List<ItemStack> getBundleItems(ItemStack pouch) {
        if (!isGreedPouch(pouch)) return List.of();
        Map<String, ItemStack> byItemId = new LinkedHashMap<>();
        BundleContents bundle = pouch.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            bundle.itemCopyStream().forEach(stack -> addByItemId(byItemId, stack));
        }
        return List.copyOf(byItemId.values());
    }

    /**
     * Apply the same insertion/capacity rules as {@link #setStoredItems} and return the
     * subset that should be visible. Entries outside this subset are ledger overflow,
     * not evidence that a player removed them from the pouch.
     */
    public static List<ItemStack> getDisplayableItems(List<ItemStack> contents) {
        List<ItemStack> visible = new ArrayList<>();
        if (contents != null) {
            for (ItemStack stack : contents) {
                if (stack == null || stack.isEmpty() || isGreedPouch(stack)) continue;
                if (visible.size() < CAPACITY) visible.add(stack.copyWithCount(1));
            }
        }
        Map<String, ItemStack> byItemId = new LinkedHashMap<>();
        visible.forEach(stack -> addByItemId(byItemId, stack));
        return List.copyOf(byItemId.values());
    }

    private static void addByItemId(Map<String, ItemStack> target, ItemStack stack) {
        if (stack == null || stack.isEmpty() || isGreedPouch(stack)) return;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null) target.putIfAbsent(id.toString(), stack.copyWithCount(1));
    }

    /** Write only CUSTOM_DATA backup without rewriting vanilla BUNDLE_CONTENTS. */
    public static void setCustomDataBackup(ItemStack pouch, List<ItemStack> contents,
                                           HolderLookup.Provider registries) {
        if (!isGreedPouch(pouch) || registries == null) return;
        CompoundTag tag = pouch.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ListTag list = new ListTag();
        if (contents != null) {
            for (ItemStack stack : contents) {
                if (stack != null && !stack.isEmpty() && !isGreedPouch(stack)) {
                    list.add(stack.copyWithCount(1).save(registries));
                }
            }
        }
        tag.put(TAG_GREED_CONTENTS, list);
        pouch.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /**
     * Fill newly available vanilla bundle capacity from the full ledger without
     * replacing existing stacks. This preserves counts and ordering created by the
     * inventory UI while allowing hidden overflow entries to become visible later.
     */
    public static void fillBundleFromLedger(ItemStack pouch, List<ItemStack> contents) {
        if (!isGreedPouch(pouch) || contents == null || contents.isEmpty()) return;
        BundleContents current = pouch.getOrDefault(
                DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        List<ItemStack> visible = new ArrayList<>(current.itemCopyStream().toList());
        int count = visible.stream().mapToInt(ItemStack::getCount).sum();
        Map<String, ItemStack> visibleByItemId = new LinkedHashMap<>();
        current.itemCopyStream().forEach(stack -> addByItemId(visibleByItemId, stack));
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty() || isGreedPouch(stack)) continue;
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null || visibleByItemId.containsKey(id.toString())) continue;
            if (count >= CAPACITY) break;
            visible.add(stack.copyWithCount(1));
            count++;
            visibleByItemId.put(id.toString(), stack.copyWithCount(1));
        }
        pouch.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(visible));
    }

    private static @Nullable UUID parseUuid(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static int storedCount(ItemStack pouch) {
        return pouch.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY)
                .itemCopyStream().mapToInt(ItemStack::getCount).sum();
    }

    /** Each physical item costs one position, including unstackable equipment. */
    public static int insert(ItemStack pouch, ItemStack offered) {
        if (!isGreedPouch(pouch) || !canStore(offered) || !offered.getItem().canFitInsideContainerItems()) return 0;
        int count = Math.min(offered.getCount(), Math.max(0, CAPACITY - storedCount(pouch)));
        if (count == 0) return 0;
        List<ItemStack> items = new ArrayList<>(pouch.getOrDefault(
                DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).itemCopyStream().toList());
        // Keep each item separate so every click can retrieve one independent item.
        for (int i = 0; i < count; i++) items.add(0, offered.copyWithCount(1));
        pouch.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
        offered.shrink(count);
        return count;
    }

    public static ItemStack removeFirst(ItemStack pouch) {
        List<ItemStack> items = new ArrayList<>(pouch.getOrDefault(
                DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).itemCopyStream().toList());
        if (items.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = items.getFirst().split(1);
        if (items.getFirst().isEmpty()) items.removeFirst();
        pouch.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
        return result;
    }
}
