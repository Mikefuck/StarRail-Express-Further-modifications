package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutVictoryChecker;
import com.habitrain.core.game.sre.role.sins.ServerAimTargeting;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.component.EnvyComponent;
import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import com.habitrain.core.game.sre.role.sins.win.SinVictoryHooks;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 贪婪：绑定收纳袋；G 偷准星目标随机一件物品（25s CD）；
 * 袋内不同种类数 &gt; 开局人数即独立胜；失袋 force 死。
 * <p>
 * 收集方式：右键袋 + 另一手物品 → 记种类并写入袋内容。
 */
public final class GreedComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<GreedComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_greed"), GreedComponent.class);

    public static final ResourceLocation GREED_LOST_POUCH = HabiTrainCore.id("greed_lost_pouch");
    public static final ResourceLocation STEAL_SKILL_ID = HabiTrainCore.id("sin_greed_steal");
    public static final int STEAL_CD_SECONDS = 25;
    public static final double STEAL_RANGE = 8.0;

    private final Player player;

    /** Fixed for the round after init. */
    private int targetCount;
    private final Set<String> collectedTypeIds = new HashSet<>();
    /** Exact physical contents of the bound pouch, including components/NBT. */
    private final List<ItemStack> storedItems = new ArrayList<>();
    private boolean pouchGiven;
    private boolean collectionComplete;
    private boolean lostPouchKilled;
    private int graceTicks = 40; // 2s after assign before lost-pouch death

    public GreedComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public int getCollectedCount() {
        return collectedTypeIds.size();
    }

    public Set<String> getCollectedTypeIds() {
        return Collections.unmodifiableSet(collectedTypeIds);
    }

    public List<ItemStack> getStoredItems() {
        return storedItems.stream().map(ItemStack::copy).toList();
    }

    public boolean isCollectionComplete() {
        return collectionComplete;
    }

    public boolean isPouchGiven() {
        return pouchGiven;
    }

    /**
     * Trade/API: add a collected type id and physical stack to pouch storage.
     *
     * @return true if the type was newly added
     */
    public boolean addStoredItem(ServerPlayer self, ItemStack actualItem) {
        return addStoredItem(self, actualItem, true);
    }

    public boolean addStoredItem(ServerPlayer self, ItemStack actualItem, boolean announce) {
        if (self == null || actualItem == null || actualItem.isEmpty() || collectionComplete
                || GreedPouchItem.isGreedPouch(actualItem)) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(actualItem.getItem());
        if (id == null) return false;
        String itemId = id.toString();
        if (collectedTypeIds.contains(itemId)) return false;
        ItemStack stored = actualItem.copyWithCount(1);
        storedItems.add(stored);
        boolean fresh = collectedTypeIds.add(itemId);
        KEY.sync(self);
        syncPhysicalPouch(self);
        if (fresh && announce) {
            self.displayClientMessage(
                    Component.translatable(
                            "message.habitrain_core.sin_greed.absorb_new",
                            stored.getHoverName().getString(),
                            collectedTypeIds.size(),
                            Math.max(1, targetCount)
                    ),
                    true
            );
            checkAndTriggerWin(self);
        }
        return fresh;
    }

    /** Exact-stack restore used only for transaction rollback. */
    public void restoreStoredItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || GreedPouchItem.isGreedPouch(stack)) return;
        storedItems.add(stack.copyWithCount(1));
        rebuildCollectedTypes();
        if (player != null) KEY.sync(player);
        if (player instanceof ServerPlayer self) syncPhysicalPouch(self);
    }

    /** Trade SELL: remove and return one exact stored stack of this item ID. */
    public ItemStack removeStoredItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) return ItemStack.EMPTY;
        for (int i = 0; i < storedItems.size(); i++) {
            ItemStack stack = storedItems.get(i);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && itemId.equals(id.toString())) {
                ItemStack removed = storedItems.remove(i);
                rebuildCollectedTypes();
                if (player != null) KEY.sync(player);
                if (player instanceof ServerPlayer self) syncPhysicalPouch(self);
                return removed;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void init() {
        clear();
        if (!(player instanceof ServerPlayer sp) || !(sp.level() instanceof ServerLevel level)) {
            return;
        }
        targetCount = computeTarget(level);
        givePouch(sp);
        sp.displayClientMessage(
                Component.translatable(
                        "message.habitrain_core.sin_greed.target",
                        targetCount,
                        getCollectedCount()
                ),
                false
        );
        KEY.sync(player);
    }

    @Override
    public void clear() {
        targetCount = 0;
        collectedTypeIds.clear();
        storedItems.clear();
        pouchGiven = false;
        collectionComplete = false;
        lostPouchKilled = false;
        graceTicks = 40;
    }

    /**
     * Win when pouch kinds &gt; start player count → need start+1 distinct kinds.
     */
    public static int computeTarget(ServerLevel level) {
        int start = resolveStartPlayers(level);
        return Math.max(1, start + 1);
    }

    /** G skill: steal one random transferable item from crosshair target. */
    public static boolean useSteal(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!(self.level() instanceof ServerLevel level)) return false;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || SevenSins.GREED == null || !game.isRole(self, SevenSins.GREED)) {
            return false;
        }
        GreedComponent greed = KEY.get(self);
        if (greed == null || !greed.pouchGiven) {
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_greed.no_pouch"),
                    true
            );
            return false;
        }

        ServerPlayer target = ServerAimTargeting.resolve(self, ctx.target(), STEAL_RANGE);
        if (target == null || target.getUUID().equals(self.getUUID()) || target.isSpectator()) {
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_greed.steal_no_target"),
                    true
            );
            return false;
        }
        try {
            if (GameUtils.isPlayerEliminated(target)) {
                self.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_greed.steal_no_target"),
                        true
                );
                return false;
            }
        } catch (Throwable ignored) {
        }

        ItemStack taken = takeRandomTransferable(target, self);
        if (taken == null || taken.isEmpty()) {
            self.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_greed.steal_empty"),
                    true
            );
            return false;
        }
        if (!self.getInventory().add(taken)) {
            self.drop(taken, false);
        }
        self.displayClientMessage(
                Component.translatable(
                        "message.habitrain_core.sin_greed.steal_ok",
                        taken.getHoverName().getString(),
                        target.getGameProfile().getName()
                ),
                true
        );
        target.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_greed.steal_victim"),
                true
        );
        HabiTrainCore.LOGGER.info("[Greed] {} stole {} from {}",
                self.getGameProfile().getName(),
                taken.getHoverName().getString(),
                target.getGameProfile().getName());
        return true;
    }

    private static ItemStack takeRandomTransferable(ServerPlayer victim, ServerPlayer recipient) {
        Inventory inv = victim.getInventory();
        List<int[]> slots = new ArrayList<>(); // [kind, index] kind 0=main 1=off
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (EnvyComponent.isTransferable(stack, recipient)) {
                slots.add(new int[]{0, i});
            }
        }
        for (int i = 0; i < inv.offhand.size(); i++) {
            ItemStack stack = inv.offhand.get(i);
            if (EnvyComponent.isTransferable(stack, recipient)) {
                slots.add(new int[]{1, i});
            }
        }
        if (slots.isEmpty()) return ItemStack.EMPTY;
        int[] pick = slots.get(ThreadLocalRandom.current().nextInt(slots.size()));
        ItemStack stack = pick[0] == 0 ? inv.items.get(pick[1]) : inv.offhand.get(pick[1]);
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack taken = stack.copyWithCount(1);
        stack.shrink(1);
        if (stack.isEmpty()) {
            if (pick[0] == 0) inv.items.set(pick[1], ItemStack.EMPTY);
            else inv.offhand.set(pick[1], ItemStack.EMPTY);
        }
        inv.setChanged();
        return taken;
    }

    private static int resolveStartPlayers(ServerLevel level) {
        if (level == null) return 1;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game != null) {
                int starting = game.getStartingPlayerCount();
                if (starting > 0) return starting;
                int count = game.getPlayerCount();
                if (count > 0) return count;
                if (game.getRoles() != null && !game.getRoles().isEmpty()) {
                    return game.getRoles().size();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            int blackout = BlackoutRoleManager.getRoleHistory(level).size();
            if (blackout > 0) return blackout;
        } catch (Throwable ignored) {
        }
        try {
            int ready = GameUtils.getParticipatingPlayerCount(level);
            if (ready > 0) return ready;
        } catch (Throwable ignored) {
        }
        int online = 0;
        for (ServerPlayer p : level.players()) {
            if (p != null && !p.isSpectator()) online++;
        }
        return Math.max(1, online);
    }

    private void givePouch(ServerPlayer sp) {
        // Remove previous own pouches to avoid duplicates on re-init
        for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
            ItemStack s = sp.getInventory().getItem(i);
            if (GreedPouchItem.isBoundPouchOf(sp, s)) {
                sp.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        ItemStack pouch = GreedPouchItem.createBoundPouch(sp);
        if (!sp.getInventory().add(pouch)) {
            // Prefer keep on player; if full, force into a slot rather than world drop
            sp.getInventory().setItem(0, pouch);
        }
        pouchGiven = true;
        GreedPouchItem.setStoredItems(pouch, storedItems, sp.registryAccess());
        graceTicks = 40;
        HabiTrainCore.LOGGER.info("[Greed] gave bound pouch to {} target={}",
                sp.getGameProfile().getName(), targetCount);
    }

    /**
     * Absorb one item type from the other hand into the collection set.
     *
     * @return true if handled (new or duplicate type)
     */
    public boolean tryAbsorbOtherHand(ServerPlayer self, ItemStack other) {
        if (self == null || other == null || other.isEmpty()) return false;
        if (GreedPouchItem.isGreedPouch(other)) return false;
        if (other.is(Items.AIR)) return false;
        if (collectionComplete) return false;

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(other.getItem());
        if (id == null) return false;
        ItemStack stored = other.copyWithCount(1);
        // Quiet add — we announce once here (avoids double message with addStoredItem).
        boolean fresh = addStoredItem(self, stored, false);

        if (fresh) {
            self.displayClientMessage(
                    Component.translatable(
                            "message.habitrain_core.sin_greed.absorb_new",
                            other.getHoverName().getString(),
                            collectedTypeIds.size(),
                            Math.max(1, targetCount)
                    ),
                    true
            );
        } else {
            self.displayClientMessage(
                    Component.translatable(
                            "message.habitrain_core.sin_greed.absorb_dup",
                            other.getHoverName().getString(),
                            collectedTypeIds.size(),
                            Math.max(1, targetCount)
                    ),
                    true
            );
        }

        // The exact item now exists in pouch storage; remove it from the offered hand.
        other.shrink(1);
        checkAndTriggerWin(self);
        return true;
    }

    public void checkAndTriggerWin(ServerPlayer self) {
        if (self == null || collectionComplete || lostPouchKilled) return;
        if (targetCount <= 0) return;
        // targetCount = startPlayers + 1  ⇔  kinds > startPlayers
        if (collectedTypeIds.size() < targetCount) return;
        collectionComplete = true;
        KEY.sync(self);
        if (!(self.level() instanceof ServerLevel level)) return;
        self.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_greed.win"),
                false
        );
        HabiTrainCore.LOGGER.info("[Greed] collection complete {} ({}/{})",
                self.getGameProfile().getName(), collectedTypeIds.size(), targetCount);
        SinVictoryHooks.triggerGreedWin(level, self);
        try {
            BlackoutVictoryChecker.endGameGreedCustom(level, self);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.debug("[Greed] blackout end skipped", t);
        }
    }

    @Override
    public void serverTick() {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel level)) return;
        if (sp.isSpectator() || !sp.isAlive()) return;
        if (lostPouchKilled || collectionComplete) return;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        boolean isGreed = false;
        try {
            if (game != null && SevenSins.GREED != null && game.isRole(sp, SevenSins.GREED)) {
                isGreed = true;
            }
        } catch (Throwable ignored) {
        }
        if (!isGreed) {
            try {
                if (SevenSins.GREED_ID.equals(BlackoutRoleManager.getRoleHistory(level).get(sp.getUUID()))) {
                    isGreed = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!isGreed) return;

        if (!pouchGiven && targetCount > 0) {
            givePouch(sp);
        }
        if (targetCount <= 0 && pouchGiven) {
            // rehydrate target if NBT missed it
            targetCount = computeTarget(level);
        }

        if (graceTicks > 0) {
            graceTicks--;
            return;
        }

        if (!GreedPouchItem.playerHasOwnPouch(sp)) {
            lostPouchKilled = true;
            KEY.sync(sp);
            sp.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_greed.lost_pouch"),
                    false
            );
            HabiTrainCore.LOGGER.info("[Greed] {} lost pouch → forceKill",
                    sp.getGameProfile().getName());
            try {
                GameUtils.forceKillPlayer(sp, true, null, GREED_LOST_POUCH);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Greed] forceKillPlayer failed, fallback killPlayer", t);
                GameUtils.killPlayer(sp, true, null, GREED_LOST_POUCH);
            }
            return;
        }

        // CRITICAL: vanilla bundle UI inserts only update BUNDLE_CONTENTS.
        // Win counter lives in collectedTypeIds — resync every tick from the physical pouch.
        resyncFromPhysicalPouch(sp);

        // Periodic progress HUD every 5s
        if (level.getGameTime() % 100L == 0L && targetCount > 0) {
            sp.displayClientMessage(
                    Component.translatable(
                            "message.habitrain_core.sin_greed.progress",
                            collectedTypeIds.size(),
                            targetCount
                    ),
                    true
            );
        }

        checkAndTriggerWin(sp);
    }

    /**
     * Rebuild collection from the bound pouch's real vanilla bundle contents.
     * This is what makes "put stolen items into the bag via inventory UI" count toward win.
     */
    private void resyncFromPhysicalPouch(ServerPlayer self) {
        if (self == null || collectionComplete) return;
        ItemStack pouch = findOwnPouch(self);
        if (pouch.isEmpty()) return;

        List<ItemStack> ledger = GreedPouchItem.getLedgerItems(pouch, self.registryAccess());
        List<ItemStack> actualVisible = GreedPouchItem.getBundleItems(pouch);

        // Migration/crash recovery: if the component lost its list but the bound item
        // still has the complete backup, restore from that backup. A normal player
        // removal cannot enter this branch because the component still has its prior list.
        if (storedItems.isEmpty() && !ledger.isEmpty() && actualVisible.isEmpty()) {
            storedItems.addAll(ledger.stream().map(ItemStack::copy).toList());
            rebuildCollectedTypes();
            GreedPouchItem.setStoredItems(pouch, storedItems, self.registryAccess());
            KEY.sync(self);
            return;
        }

        // The component is normally authoritative. The ledger is a crash/migration fallback
        // and, unlike the vanilla bundle, contains entries hidden by the weight limit.
        Map<String, ItemStack> previous = new LinkedHashMap<>();
        for (ItemStack stack : storedItems) addStoredById(previous, stack);
        for (ItemStack stack : ledger) addStoredById(previous, stack);
        if (previous.isEmpty() && actualVisible.isEmpty()) return;

        Set<String> actualVisibleIds = itemIds(actualVisible);
        List<ItemStack> expectedVisible = GreedPouchItem.getDisplayableItems(
                new ArrayList<>(previous.values()));

        Map<String, ItemStack> nextStoredById = new LinkedHashMap<>(previous);
        // Only a previously displayable entry can prove removal. Capacity overflow must stay.
        for (String expectedId : itemIds(expectedVisible)) {
            if (!actualVisibleIds.contains(expectedId)) {
                nextStoredById.remove(expectedId);
            }
        }
        // Vanilla inventory UI writes newly inserted entries only to BUNDLE_CONTENTS.
        for (ItemStack stack : actualVisible) addStoredById(nextStoredById, stack);

        int before = collectedTypeIds.size();
        Set<String> next = new HashSet<>(nextStoredById.keySet());
        List<ItemStack> nextStored = new ArrayList<>(nextStoredById.values());

        // Detect newly added kinds vs previous set for feedback.
        Set<String> newly = new HashSet<>(next);
        newly.removeAll(collectedTypeIds);

        boolean changed = !next.equals(collectedTypeIds);
        if (!changed) return;

        collectedTypeIds.clear();
        collectedTypeIds.addAll(next);
        storedItems.clear();
        storedItems.addAll(nextStored);
        // Preserve actual vanilla stack counts/order while promoting ledger overflow
        // into capacity opened by a removed visible entry.
        GreedPouchItem.setCustomDataBackup(pouch, storedItems, self.registryAccess());
        GreedPouchItem.fillBundleFromLedger(pouch, storedItems);
        KEY.sync(self);

        if (!newly.isEmpty()) {
            for (String idStr : newly) {
                ResourceLocation rid = ResourceLocation.tryParse(idStr);
                String label = idStr;
                if (rid != null) {
                    try {
                        label = BuiltInRegistries.ITEM.get(rid).getDefaultInstance()
                                .getHoverName().getString();
                    } catch (Throwable ignored) {
                    }
                }
                self.displayClientMessage(
                        Component.translatable(
                                "message.habitrain_core.sin_greed.absorb_new",
                                label,
                                collectedTypeIds.size(),
                                Math.max(1, targetCount)
                        ),
                        true
                );
            }
            HabiTrainCore.LOGGER.info("[Greed] {} pouch resync {} -> {} kinds (new={})",
                    self.getGameProfile().getName(), before, collectedTypeIds.size(), newly.size());
        }
    }

    private static void addStoredById(Map<String, ItemStack> target, ItemStack stack) {
        if (stack == null || stack.isEmpty() || GreedPouchItem.isGreedPouch(stack)) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null) target.putIfAbsent(id.toString(), stack.copyWithCount(1));
    }

    private static Set<String> itemIds(List<ItemStack> stacks) {
        Set<String> ids = new HashSet<>();
        if (stacks == null) return ids;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty() || GreedPouchItem.isGreedPouch(stack)) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) ids.add(id.toString());
        }
        return ids;
    }

    private static ItemStack findOwnPouch(ServerPlayer self) {
        for (int i = 0; i < self.getInventory().getContainerSize(); i++) {
            ItemStack stack = self.getInventory().getItem(i);
            if (GreedPouchItem.isBoundPouchOf(self, stack)) {
                return stack;
            }
        }
        try {
            ItemStack cursor = self.containerMenu != null ? self.containerMenu.getCarried() : ItemStack.EMPTY;
            if (GreedPouchItem.isBoundPouchOf(self, cursor)) return cursor;
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putInt("Target", targetCount);
        tag.putBoolean("PouchGiven", pouchGiven);
        tag.putBoolean("Complete", collectionComplete);
        tag.putBoolean("LostKill", lostPouchKilled);
        ListTag list = new ListTag();
        for (ItemStack stack : storedItems) {
            if (stack != null && !stack.isEmpty()) {
                list.add(stack.save(registryLookup));
            }
        }
        tag.put("StoredItems", list);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        targetCount = tag.getInt("Target");
        pouchGiven = tag.getBoolean("PouchGiven");
        collectionComplete = tag.getBoolean("Complete");
        lostPouchKilled = tag.getBoolean("LostKill");
        collectedTypeIds.clear();
        storedItems.clear();
        ListTag list = tag.getList("StoredItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack parsed = ItemStack.parseOptional(registryLookup, list.getCompound(i));
            if (!parsed.isEmpty() && !GreedPouchItem.isGreedPouch(parsed)) {
                storedItems.add(parsed.copyWithCount(1));
            }
        }
        rebuildCollectedTypes();
    }

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        writeToSyncNbt(tag, registryLookup);
        tag.putInt("Grace", graceTicks);
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        readFromSyncNbt(tag, registryLookup);
        if (tag.contains("Grace")) {
            graceTicks = tag.getInt("Grace");
        }
    }

    private void rebuildCollectedTypes() {
        collectedTypeIds.clear();
        for (ItemStack stack : storedItems) {
            if (stack == null || stack.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) collectedTypeIds.add(id.toString());
        }
    }

    private void syncPhysicalPouch(ServerPlayer self) {
        for (int i = 0; i < self.getInventory().getContainerSize(); i++) {
            ItemStack stack = self.getInventory().getItem(i);
            if (GreedPouchItem.isBoundPouchOf(self, stack)) {
                GreedPouchItem.setStoredItems(stack, storedItems, self.registryAccess());
                self.getInventory().setChanged();
                return;
            }
        }
    }
}
