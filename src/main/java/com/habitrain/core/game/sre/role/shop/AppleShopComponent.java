package com.habitrain.core.game.sre.role.shop;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.game.ShopContent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.List;
import java.util.Map;

/** Target-side, owner-only round state. Never modifies upstream discounts. */
public final class AppleShopComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<AppleShopComponent> KEY = ComponentRegistry.getOrCreate(
            HabiTrainCore.id("apple_shop"), AppleShopComponent.class);

    private final Player player;
    private final ApplePriceState<AppleShopSignature> state = new ApplePriceState<>();
    private int shopSize;
    private int purchaseIndex = -1;
    private ApplePriceState.Quote<AppleShopSignature> purchaseQuote;

    public AppleShopComponent(Player player) { this.player = player; }
    @Override public Player getPlayer() { return player; }
    @Override public boolean shouldSyncWith(ServerPlayer recipient) { return player == recipient; }
    @Override public void init() { clear(); }

    public void apply(String role, List<ShopEntry> entries,
                      Map<Integer, ApplePriceState.Quote<AppleShopSignature>> quotes) {
        shopSize = entries.size();
        state.apply(role, player.level().getGameTime() + SnakeFruitPricePolicy.DURATION_TICKS,
                player.getRandom().nextLong(), player.getRandom().nextLong(), false, quotes);
        sync();
    }

    public ApplePriceState.Offer<AppleShopSignature> offer(int index, ShopEntry entry, int basePrice) {
        if (state.isEmpty()) return null;
        var game = SREGameWorldComponent.KEY.get(player.level());
        var role = game.getRole(player);
        if (!game.isRunning() || role == null) return null;
        return state.offer(role.identifier().toString(), index, AppleShopSignature.of(entry, basePrice),
                player.level().getGameTime());
    }

    public ApplePriceState.Quote<AppleShopSignature> quote(int index, ShopEntry entry) {
        return quote(index, entry, entry.price());
    }

    public ApplePriceState.Quote<AppleShopSignature> quote(int index, ShopEntry entry, int basePrice) {
        var offer = offer(index, entry, basePrice);
        return offer == null ? null : offer.quote();
    }

    public static int effectivePrice(Player player, int index, ShopEntry entry, int normalPrice) {
        if (player == null) return normalPrice;
        var component = KEY.get(player);
        if (component.purchaseIndex == index && component.purchaseQuote != null
                && component.purchaseQuote.signature().equals(AppleShopSignature.of(entry))) {
            return component.purchaseQuote.price();
        }
        var quote = component.quote(index, entry);
        return quote == null ? normalPrice : quote.price();
    }

    /** Prevent the old index-only packet from silently charging a newly jumped price. */
    public boolean canPurchase(int index) {
        if (purchaseQuote != null && purchaseIndex == index) return true;
        var entries = currentEntries();
        if (entries == null || index < 0 || index >= entries.size()
                || quote(index, entries.get(index)) == null) return true;
        rejectQuote();
        return false;
    }

    public void buy(int index, long revision, int frame) {
        if (!(player instanceof ServerPlayer) || purchaseQuote != null) return;
        var entries = currentEntries();
        if (entries == null || !matchesShop(entries) || index < 0 || index >= entries.size()) {
            rejectQuote();
            return;
        }
        long now = player.level().getGameTime();
        if (state.settle(now)) sync();
        var quote = state.purchaseQuote(state.role(), index, AppleShopSignature.of(entries.get(index)),
                now, revision, frame);
        if (quote == null) {
            rejectQuote();
            return;
        }
        purchaseIndex = index;
        purchaseQuote = quote;
        try {
            SREPlayerShopComponent.KEY.get(player).tryBuy(index);
        } finally {
            purchaseQuote = null;
            purchaseIndex = -1;
        }
    }

    private void rejectQuote() {
        player.displayClientMessage(Component.translatable("message.habitrain_core.snake_fruit.quote_expired")
                .withStyle(ChatFormatting.RED), true);
        sync();
    }

    private List<ShopEntry> currentEntries() {
        var game = SREGameWorldComponent.KEY.get(player.level());
        var role = game.getRole(player);
        return game.isRunning() && GameUtils.isPlayerAliveAndSurvival(player) && role != null
                && state.active(role.identifier().toString()) ? ShopContent.getShopEntries(role.identifier()) : null;
    }

    private boolean matchesShop(List<ShopEntry> entries) {
        return entries.size() == shopSize && state.quotes().entrySet().stream().allMatch(e ->
                e.getKey() >= 0 && e.getKey() < entries.size()
                        && e.getValue().signature().equals(AppleShopSignature.of(entries.get(e.getKey()))));
    }

    @Override public void clear() {
        if (state.isEmpty()) return;
        state.clear();
        shopSize = 0;
        sync();
    }

    private void sync() {
        if (!player.level().isClientSide) KEY.sync(player);
    }

    @Override public void serverTick() {
        if (state.isEmpty()) return;
        var entries = currentEntries();
        if (entries == null || !matchesShop(entries)) {
            clear();
            return;
        }
        if (state.settle(player.level().getGameTime())) {
            sync();
            player.displayClientMessage(Component.translatable("message.habitrain_core.snake_fruit.settled"), true);
        }
    }

    @Override public void writeToSyncNbt(CompoundTag tag, HolderLookup.Provider registries) {
        AppleShopStateCodec.write(state, shopSize, tag, registries);
    }

    @Override public void readFromSyncNbt(CompoundTag tag, HolderLookup.Provider registries) {
        shopSize = AppleShopStateCodec.read(state, tag, registries);
    }

    @Override public void writeToNbt(CompoundTag tag, HolderLookup.Provider registries) { }
    @Override public void readFromNbt(CompoundTag tag, HolderLookup.Provider registries) { }
}
