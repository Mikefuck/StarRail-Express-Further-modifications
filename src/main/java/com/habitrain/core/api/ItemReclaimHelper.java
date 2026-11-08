package com.habitrain.core.api;

import com.habitrain.core.api.spi.CoreSpi;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 任务道具回收辅助类。
 *
 * 设计意图：
 *   任务系统给予玩家的物理道具（如 search_backpack 翻出的撬棍/手铐等），
 *   在任务因取消/隐藏而失效时应被回收，避免玩家无奖励获得强大道具。
 *
 * 机制：
 *   1. 发放时：给 ItemStack 打 CUSTOM_DATA NBT 标签 habitrain_grant = 任务 fullId
 *      （见 {@link #tagGrantedItem}）。不维护 TaskInstance 列表。
 *   2. 回收时：扫描主背包 + 盔甲 + 副手 + 2x2 合成格/打开容器 + 鼠标携带 + ExtraSlot + 附近地面掉落物，
 *      移除所有带匹配标签的 ItemStack。
 *
 * 注意：成功完成的任务不回收（玩家保留道具作为奖励）。
 *       仅在取消/隐藏路径（init/clear/forceReplace/timeout/fail）调用 reclaim。
 *
 * <p>额外槽位（上游 {@code ExtraSlotComponent}）经
 * {@link com.habitrain.core.api.spi.ExtraSlotReclaimBridge} 注入，本类不再编译期依赖上游组件。
 */
public final class ItemReclaimHelper {

    /** NBT 标签 key，记录道具是哪个任务发放的 */
    public static final String GRANT_TAG_KEY = "habitrain_grant";
    public static final String GRANT_OWNER_TAG_KEY = "habitrain_grant_owner";
    public static final String GRANT_INSTANCE_TAG_KEY = "habitrain_grant_instance";

    private ItemReclaimHelper() {}

    /**
     * 给 ItemStack 打 habitrain_grant 标签。
     * 使用 CUSTOM_DATA 组件存 NBT（1.21+ 推荐方式，ItemStack 自身 NBT 已迁移至组件）。
     */
    public static ItemStack tagGrantedItem(ItemStack stack, String fullId) {
        if (stack == null || stack.isEmpty() || fullId == null || fullId.isBlank()) return stack;
        CompoundTag tag = stack.getOrDefault(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        tag.putString(GRANT_TAG_KEY, fullId);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        return stack;
    }

    /** Tags one player's grant; prefer the task overload when an assignment is available. */
    public static ItemStack tagGrantedItem(ItemStack stack, String fullId, UUID ownerId) {
        return tagGrantedItem(stack, fullId, ownerId, null);
    }

    /** Tags the exact assignment so a later same-type task cannot reclaim an earlier reward. */
    public static ItemStack tagGrantedItem(ItemStack stack, Player player, TaskInstance task) {
        if (player == null || task == null) return stack;
        return tagGrantedItem(stack, task.getFullId(), player.getUUID(), task.getInstanceId());
    }

    private static ItemStack tagGrantedItem(ItemStack stack, String fullId, UUID ownerId, UUID instanceId) {
        tagGrantedItem(stack, fullId);
        if (!matchesGrant(stack, fullId) || ownerId == null) return stack;
        CompoundTag tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        tag.putUUID(GRANT_OWNER_TAG_KEY, ownerId);
        if (instanceId != null) tag.putUUID(GRANT_INSTANCE_TAG_KEY, instanceId);
        else tag.remove(GRANT_INSTANCE_TAG_KEY);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        return stack;
    }

    /**
     * 打标后放入背包；API-TV-12：拒绝无 fullId 的未打标发放，且背包与副手均满时不再丢在地上（防止借满包丢地偷渡道具）。
     */
    public static void giveTaggedItem(Player player, ItemStack stack, String fullId) {
        if (player == null || stack == null || stack.isEmpty() || fullId == null || fullId.isBlank()) return;
        ItemStack tagged = tagGrantedItem(stack, fullId, player.getUUID());
        if (!player.getInventory().add(tagged)) {
            Inventory inv = player.getInventory();
            if (!inv.offhand.isEmpty() && inv.offhand.get(0).isEmpty()) {
                inv.offhand.set(0, tagged.copy());
                tagged.setCount(0);
                inv.setChanged();
            } else {
                // 背包与副手全满时不向地面生成掉落物实体，避免绕过背包回收把任务道具留到下一局
                tagged.setCount(0);
            }
        }
    }

    /**
     * 回收主背包 + 盔甲 + 副手 + 容器槽位（含 2x2 合成栏） + 鼠标携带 + ExtraSlot + 周围地面掉落物中
     * 所有带匹配 habitrain_grant 标签的 ItemStack。
     */
    public static void reclaim(Player player, String fullId) {
        reclaim(player, fullId, null);
    }

    private static void reclaim(Player player, String fullId, UUID instanceId) {
        if (player == null || fullId == null || fullId.isBlank()) return;
        UUID ownerId = player.getUUID();
        Predicate<ItemStack> owned = stack -> matchesReclaimGrant(stack, fullId, ownerId, instanceId, true);
        Predicate<ItemStack> shared = stack -> matchesReclaimGrant(stack, fullId, ownerId, instanceId, false);
        Inventory inv = player.getInventory();
        boolean changed = false;

        // 主背包（36格）
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (owned.test(stack)) {
                stack.setCount(0);
                inv.items.set(i, ItemStack.EMPTY);
                changed = true;
            }
        }
        // 装备栏（4格）
        for (int i = 0; i < inv.armor.size(); i++) {
            ItemStack stack = inv.armor.get(i);
            if (owned.test(stack)) {
                stack.setCount(0);
                inv.armor.set(i, ItemStack.EMPTY);
                changed = true;
            }
        }
        // 副手
        for (int i = 0; i < inv.offhand.size(); i++) {
            ItemStack stack = inv.offhand.get(i);
            if (owned.test(stack)) {
                stack.setCount(0);
                inv.offhand.set(i, ItemStack.EMPTY);
                changed = true;
            }
        }
        // API-TV-12：扫描玩家 2x2 合成栏与当前打开的容器菜单槽位
        changed |= reclaimMenuSlots(player.inventoryMenu, owned);
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            changed |= reclaimMenuSlots(player.containerMenu, shared);
        }
        changed |= reclaimMenuCarried(player.containerMenu, owned);
        if (player.inventoryMenu != player.containerMenu) {
            changed |= reclaimMenuCarried(player.inventoryMenu, owned);
        }
        CoreSpi.reclaimExtraSlots(player, owned);
        reclaimDroppedItems(player, shared);
        inv.setChanged();
        // API-TV-12：无论是否只改了背包槽或合成槽，均同步菜单变更以消除客户端幽灵图标
        if (changed) {
            if (player.containerMenu != null) {
                player.containerMenu.broadcastChanges();
            }
            if (player.inventoryMenu != null && player.inventoryMenu != player.containerMenu) {
                player.inventoryMenu.broadcastChanges();
            }
        }
    }

    /** 扫描容器菜单中的所有槽位（如 2x2 合成格、打开的箱子等）。 */
    private static boolean reclaimMenuSlots(AbstractContainerMenu menu, Predicate<ItemStack> owned) {
        if (menu == null || menu.slots == null) {
            return false;
        }
        boolean changed = false;
        for (Slot slot : menu.slots) {
            if (slot == null || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (owned.test(stack)) {
                stack.setCount(0);
                slot.set(ItemStack.EMPTY);
                changed = true;
            }
        }
        return changed;
    }

    /** 1.21：鼠标携带栈在菜单上，不在 Inventory.carried。 */
    private static boolean reclaimMenuCarried(AbstractContainerMenu menu, Predicate<ItemStack> owned) {
        if (menu == null) {
            return false;
        }
        ItemStack carried = menu.getCarried();
        if (!owned.test(carried)) {
            return false;
        }
        carried.setCount(0);
        menu.setCarried(ItemStack.EMPTY);
        return true;
    }

    /** API-TV-12：清理玩家附近丢在地面上的带标签任务道具实体。 */
    private static void reclaimDroppedItems(Player player, Predicate<ItemStack> owned) {
        try {
            var level = player.level();
            if (level == null || level.isClientSide()) {
                return;
            }
            List<ItemEntity> drops = level.getEntitiesOfClass(
                    ItemEntity.class,
                    player.getBoundingBox().inflate(64.0),
                    entity -> entity != null && entity.isAlive() && owned.test(entity.getItem()));
            for (ItemEntity drop : drops) {
                drop.setItem(ItemStack.EMPTY);
                drop.discard();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean matchesReclaimGrant(ItemStack stack, String fullId, UUID ownerId,
                                               UUID instanceId, boolean allowLegacyUnowned) {
        if (stack == null || stack.isEmpty()) return false;
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data != null && matchesReclaimTag(data.copyTag(), fullId, ownerId, instanceId, allowLegacyUnowned);
    }

    // Legacy unowned tags are safe to reclaim only in the player's own inventory,
    // crafting grid, carried stack and extra slots, never from shared containers/ground.
    static boolean matchesReclaimTag(CompoundTag tag, String fullId, UUID ownerId,
                                     UUID instanceId, boolean allowLegacyUnowned) {
        if (fullId == null || !fullId.equals(tag.getString(GRANT_TAG_KEY))) return false;
        if (tag.hasUUID(GRANT_OWNER_TAG_KEY)) {
            if (!tag.getUUID(GRANT_OWNER_TAG_KEY).equals(ownerId)) return false;
        } else if (!allowLegacyUnowned) {
            return false;
        }
        return instanceId == null || !tag.hasUUID(GRANT_INSTANCE_TAG_KEY)
                || instanceId.equals(tag.getUUID(GRANT_INSTANCE_TAG_KEY));
    }

    /** 检查 ItemStack 是否带匹配的 habitrain_grant 标签 */
    public static boolean matchesGrant(ItemStack stack, String fullId) {
        if (stack == null || stack.isEmpty() || fullId == null) return false;
        var customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (customData == null) return false;
        CompoundTag tag = customData.copyTag();
        return tag.contains(GRANT_TAG_KEY) && fullId.equals(tag.getString(GRANT_TAG_KEY));
    }

    /**
     * 便捷方法：在任务取消/隐藏路径调用。
     * 先 fire onReclaim（任务自定义清理），再扫描回收带标签的道具。
     */
    public static void reclaimForTask(Player player, TaskInstance task) {
        if (player == null || task == null) return;
        Throwable callbackFailure = null;
        try {
            task.getDefinition().onReclaim(player, task);
        } catch (RuntimeException | Error failure) {
            callbackFailure = failure;
            throw failure;
        } finally {
            // A provider callback must never prevent Core's tagged-item cleanup.
            try {
                reclaim(player, task.getFullId(), task.getInstanceId());
            } catch (RuntimeException | Error reclaimFailure) {
                if (callbackFailure == null) {
                    throw reclaimFailure;
                }
                if (callbackFailure != reclaimFailure) {
                    callbackFailure.addSuppressed(reclaimFailure);
                }
            }
        }
    }
}
