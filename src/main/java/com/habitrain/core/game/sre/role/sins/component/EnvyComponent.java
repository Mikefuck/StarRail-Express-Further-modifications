package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.ItemReclaimHelper;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.ServerAimTargeting;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.content.item.IronDoorKeyItem;
import io.wifi.starrailexpress.content.item.KeyItem;
import io.wifi.starrailexpress.index.SREDataComponentTypes;
import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 嫉妒：G 标记准星玩家（90s）；仅当前标记可伤；钱比标记多则无法击杀标记。
 * 客户端透视用 CCA 同步标记目标余额对比色。
 */
public final class EnvyComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<EnvyComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_envy"), EnvyComponent.class);

    public static final ResourceLocation MARK_SKILL_ID = HabiTrainCore.id("sin_envy_mark");
    public static final int MARK_CD_SECONDS = 90;
    public static final double MARK_RANGE = 16.0;
    public static final int COIN_STEAL_MAX = 100;

    private final Player player;
    private @Nullable UUID markedUuid;
    private final Set<UUID> everMarked = new HashSet<>();
    /** Client/server: self balance for instinct color compare. */
    private int selfBalance;
    /** Client/server: last known balances of ever-marked targets. */
    private final Map<UUID, Integer> knownBalances = new HashMap<>();

    public EnvyComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public @Nullable UUID getMarkedUuid() {
        return markedUuid;
    }

    public void setMarkedUuid(@Nullable UUID uuid) {
        this.markedUuid = uuid;
        if (uuid != null) {
            everMarked.add(uuid);
        }
        KEY.sync(player);
    }

    public boolean isMark(Player target) {
        return target != null && markedUuid != null && markedUuid.equals(target.getUUID());
    }

    public boolean hasEverMarked(UUID id) {
        return id != null && everMarked.contains(id);
    }

    /** Only the currently marked player can be harmed / killed. */
    public boolean canHarm(UUID targetId) {
        return targetId != null && markedUuid != null && markedUuid.equals(targetId);
    }

    public boolean canHarm(Player target) {
        return target != null && canHarm(target.getUUID());
    }

    public Set<UUID> getEverMarked() {
        return everMarked;
    }

    public int getSelfBalance() {
        return selfBalance;
    }

    public int getKnownBalance(UUID id) {
        if (id == null) return 0;
        return knownBalances.getOrDefault(id, 0);
    }

    public static boolean useMark(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!(self.level() instanceof ServerLevel level)) return false;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || SevenSins.ENVY == null || !game.isRole(self, SevenSins.ENVY)) {
            return false;
        }

        ServerPlayer target = resolveTarget(self, ctx.target());
        if (target == null || target.getUUID().equals(self.getUUID())) {
            self.displayClientMessage(Component.literal("§c[嫉妒] 未找到可标记的目标。"), true);
            return false;
        }

        EnvyComponent c = KEY.get(self);
        c.setMarkedUuid(target.getUUID());
        self.displayClientMessage(
                Component.literal("§a[嫉妒] 已标记 " + target.getGameProfile().getName() + "。"),
                true
        );
        target.displayClientMessage(Component.literal("§c你感到被嫉妒盯上了……"), true);
        HabiTrainCore.LOGGER.debug("[Envy] {} marked {}",
                self.getGameProfile().getName(), target.getGameProfile().getName());
        return true;
    }

    private static ServerPlayer resolveTarget(ServerPlayer self, UUID targetId) {
        return ServerAimTargeting.resolve(self, targetId, MARK_RANGE);
    }

    /**
     * 可被嫉妒掠夺的物品：空/AIR/钥匙/任务发放物/灵魂绑定 OWNER 不匹配/明显不可转移 → false。
     */
    public static boolean isTransferable(ItemStack stack, @Nullable Player recipient) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.is(Items.AIR)) return false;

        Item item = stack.getItem();
        if (item == null || item == Items.AIR) return false;
        if (item instanceof KeyItem || item instanceof IronDoorKeyItem) {
            return false;
        }
        if (item == TMMItems.KEY || item == TMMItems.IRON_DOOR_KEY) {
            return false;
        }

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id != null) {
            String path = id.getPath().toLowerCase(Locale.ROOT);
            String full = id.toString().toLowerCase(Locale.ROOT);
            if (path.contains("key") || path.contains("keyblank") || path.contains("master_key")
                    || path.contains("artisan_key") || full.contains("key_blank")) {
                return false;
            }
            // Role-bound / non-physical shop consumables
            if (item == TMMItems.PSYCHO_MODE || item == TMMItems.BLACKOUT) {
                return false;
            }
        }

        // Task-granted items (habitrain_grant in CUSTOM_DATA)
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom != null) {
            CompoundTag tag = custom.copyTag();
            if (tag.contains(ItemReclaimHelper.GRANT_TAG_KEY)) {
                return false;
            }
        }

        // Greed bound pouch is never transferable (steal/loot would kill owner via lost-pouch).
        if (com.habitrain.core.game.sre.role.sins.item.GreedPouchItem.isGreedPouch(stack)) {
            return false;
        }

        // Soulbound-style OWNER string: only transferable if matches recipient UUID
        try {
            if (stack.has(SREDataComponentTypes.OWNER)) {
                String owner = stack.get(SREDataComponentTypes.OWNER);
                if (owner != null && !owner.isEmpty()) {
                    if (recipient == null) return false;
                    String recip = recipient.getUUID().toString();
                    String recipName = recipient.getGameProfile().getName();
                    if (!owner.equals(recip) && !owner.equalsIgnoreCase(recipName)) {
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {
            // component may be unavailable mid-load
        }

        return true;
    }

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        markedUuid = null;
        everMarked.clear();
        selfBalance = 0;
        knownBalances.clear();
    }

    @Override
    public void serverTick() {
        if (player.level().isClientSide) return;
        if (!(player instanceof ServerPlayer self)) return;
        if (!(self.level() instanceof ServerLevel level)) return;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        boolean isEnvy = game != null && SevenSins.ENVY != null && game.isRole(self, SevenSins.ENVY);
        if (!isEnvy || self.isSpectator() || everMarked.isEmpty()) {
            return;
        }

        // Refresh balances every second for client instinct colors.
        if (level.getGameTime() % 20L != 0L) return;

        int nextSelf = shopBalance(self);
        Map<UUID, Integer> nextKnown = new HashMap<>();
        for (UUID id : everMarked) {
            ServerPlayer other = level.getServer().getPlayerList().getPlayer(id);
            if (other == null || other.isSpectator()) continue;
            nextKnown.put(id, shopBalance(other));
        }

        boolean changed = nextSelf != selfBalance || !nextKnown.equals(knownBalances);
        selfBalance = nextSelf;
        knownBalances.clear();
        knownBalances.putAll(nextKnown);
        if (changed) {
            KEY.sync(self);
        }
    }

    private static int shopBalance(ServerPlayer p) {
        try {
            SREPlayerShopComponent shop = SREPlayerShopComponent.KEY.get(p);
            return shop != null ? shop.balance : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        if (markedUuid != null) {
            tag.putUUID("Marked", markedUuid);
        }
        ListTag ever = new ListTag();
        for (UUID id : everMarked) {
            ever.add(StringTag.valueOf(id.toString()));
        }
        tag.put("EverMarked", ever);
        tag.putInt("SelfBal", selfBalance);
        ListTag bals = new ListTag();
        for (Map.Entry<UUID, Integer> e : knownBalances.entrySet()) {
            CompoundTag line = new CompoundTag();
            line.putUUID("Id", e.getKey());
            line.putInt("Bal", e.getValue());
            bals.add(line);
        }
        tag.put("KnownBal", bals);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        if (tag.hasUUID("Marked")) {
            markedUuid = tag.getUUID("Marked");
        } else {
            markedUuid = null;
        }
        everMarked.clear();
        if (tag.contains("EverMarked", Tag.TAG_LIST)) {
            ListTag ever = tag.getList("EverMarked", Tag.TAG_STRING);
            for (int i = 0; i < ever.size(); i++) {
                try {
                    everMarked.add(UUID.fromString(ever.getString(i)));
                } catch (Throwable ignored) {
                }
            }
        }
        selfBalance = tag.getInt("SelfBal");
        knownBalances.clear();
        if (tag.contains("KnownBal", Tag.TAG_LIST)) {
            ListTag bals = tag.getList("KnownBal", Tag.TAG_COMPOUND);
            for (int i = 0; i < bals.size(); i++) {
                CompoundTag line = bals.getCompound(i);
                if (!line.hasUUID("Id")) continue;
                knownBalances.put(line.getUUID("Id"), line.getInt("Bal"));
            }
        }
    }

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        writeToSyncNbt(tag, registryLookup);
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        readFromSyncNbt(tag, registryLookup);
    }
}
