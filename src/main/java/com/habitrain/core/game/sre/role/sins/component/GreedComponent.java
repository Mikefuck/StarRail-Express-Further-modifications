package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutVictoryChecker;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import com.habitrain.core.game.sre.role.sins.win.SinVictoryHooks;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 贪婪：绑定收纳袋；收集不同 item id 达 {@code ceil(开局人数 × 2.5)} 独立胜；失袋 force 死。
 * <p>
 * 收集 MVP：右键袋 + 另一手物品 → 记种类（不真正塞进容器）。
 */
public final class GreedComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<GreedComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_greed"), GreedComponent.class);

    public static final ResourceLocation GREED_LOST_POUCH = HabiTrainCore.id("greed_lost_pouch");
    public static final double TARGET_MULTIPLIER = 2.5;

    private final Player player;

    /** Fixed for the round after init. */
    private int targetCount;
    private final Set<String> collectedTypeIds = new HashSet<>();
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

    public boolean isCollectionComplete() {
        return collectionComplete;
    }

    public boolean isPouchGiven() {
        return pouchGiven;
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
        pouchGiven = false;
        collectionComplete = false;
        lostPouchKilled = false;
        graceTicks = 40;
    }

    public static int computeTarget(ServerLevel level) {
        int start = resolveStartPlayers(level);
        return Math.max(1, (int) Math.ceil(start * TARGET_MULTIPLIER));
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
        graceTicks = 40;
        HabiTrainCore.LOGGER.info("[Greed] gave bound pouch to {} target={}",
                sp.getGameProfile().getName(), targetCount);
    }

    /**
     * Absorb one item type from the other hand into the collection set.
     *
     * @return true if a new type was recorded (or already had type — still "handled")
     */
    public boolean tryAbsorbOtherHand(ServerPlayer self, ItemStack other) {
        if (self == null || other == null || other.isEmpty()) return false;
        if (GreedPouchItem.isGreedPouch(other)) return false;
        if (other.is(Items.AIR)) return false;
        if (collectionComplete) return false;

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(other.getItem());
        if (id == null) return false;
        String key = id.toString();
        boolean fresh = collectedTypeIds.add(key);
        KEY.sync(self);

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

        // Consume one item from the offered stack (MVP: types matter; still costs the sample)
        other.shrink(1);
        checkAndTriggerWin(self);
        return true;
    }

    public void checkAndTriggerWin(ServerPlayer self) {
        if (self == null || collectionComplete || lostPouchKilled) return;
        if (targetCount <= 0) return;
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

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putInt("Target", targetCount);
        tag.putBoolean("PouchGiven", pouchGiven);
        tag.putBoolean("Complete", collectionComplete);
        tag.putBoolean("LostKill", lostPouchKilled);
        ListTag list = new ListTag();
        for (String id : collectedTypeIds) {
            list.add(StringTag.valueOf(id));
        }
        tag.put("Collected", list);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        targetCount = tag.getInt("Target");
        pouchGiven = tag.getBoolean("PouchGiven");
        collectionComplete = tag.getBoolean("Complete");
        lostPouchKilled = tag.getBoolean("LostKill");
        collectedTypeIds.clear();
        ListTag list = tag.getList("Collected", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            collectedTypeIds.add(list.getString(i));
        }
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
}
