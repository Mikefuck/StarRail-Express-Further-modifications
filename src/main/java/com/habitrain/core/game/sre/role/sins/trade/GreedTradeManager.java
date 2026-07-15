package com.habitrain.core.game.sre.role.sins.trade;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.component.GreedComponent;
import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import com.habitrain.core.network.GreedTradePromptPayload;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 贪婪匿名交易 MVP：服务端会话 + 双确认（聊天点击 / C2S）+ 原子结算。
 * <p>
 * 流程：贪婪 G 技能对准目标，另一手持样本物品 → 若已收集该种类则发起「卖出」，否则「买入」。
 * 双方 30s 内均确认后二次校验（金币 / 袋归属 / 物品 / 成交上限）再转账与改收集表。
 * <p>
 * 永不转移收纳袋；成交不改变 pouch OWNER。
 */
public final class GreedTradeManager {
    public static final int TIMEOUT_TICKS = 20 * 30;
    public static final double TRADE_RANGE = 12.0;

    public enum Side {
        /** 贪婪从收集表卖出 1 种类 → 对方付金并获得 1 个该物品 */
        SELL,
        /** 贪婪从对方背包买入 1 个样本 → 付金并记入收集表 */
        BUY
    }

    public static final class Session {
        public final UUID id;
        public final UUID greedUuid;
        public final UUID partnerUuid;
        public final Side side;
        public final String itemId;
        public final int price;
        public final long createdGameTime;
        public final ResourceLocation levelDim;
        public boolean greedConfirmed;
        public boolean partnerConfirmed;
        public boolean closed;

        Session(UUID id, UUID greedUuid, UUID partnerUuid, Side side, String itemId,
                int price, long createdGameTime, ResourceLocation levelDim) {
            this.id = id;
            this.greedUuid = greedUuid;
            this.partnerUuid = partnerUuid;
            this.side = side;
            this.itemId = itemId;
            this.price = price;
            this.createdGameTime = createdGameTime;
            this.levelDim = levelDim;
        }
    }

    private static final ConcurrentHashMap<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    /** player → active session (one at a time) */
    private static final ConcurrentHashMap<UUID, UUID> PLAYER_SESSION = new ConcurrentHashMap<>();

    private GreedTradeManager() {}

    public static void clearAll() {
        SESSIONS.clear();
        PLAYER_SESSION.clear();
        GreedDealTracker.clearAll();
    }

    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        long now = server.overworld() != null ? server.overworld().getGameTime() : 0L;
        Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Session s = it.next().getValue();
            if (s.closed) {
                it.remove();
                continue;
            }
            ServerLevel level = resolveLevel(server, s);
            long t = level != null ? level.getGameTime() : now;
            if (t - s.createdGameTime > TIMEOUT_TICKS) {
                cancelSession(server, s, "timeout");
            }
        }
    }

    /**
     * G 技能入口：准星附近玩家 + 另一手样本物品。
     */
    public static boolean useTradeSkill(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer greed = ctx.player();
        if (greed == null || greed.isSpectator()) return false;
        if (!(greed.level() instanceof ServerLevel level)) return false;
        if (!isGreedRole(level, greed)) {
            greed.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_not_greed"), true);
            return false;
        }
        if (!GreedPouchItem.playerHasOwnPouch(greed)) {
            greed.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_no_pouch"), true);
            return false;
        }

        ServerPlayer partner = resolveTarget(greed, ctx.target());
        if (partner == null || partner.getUUID().equals(greed.getUUID()) || partner.isSpectator()) {
            greed.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_no_target"), true);
            return false;
        }

        ItemStack sample = resolveSample(greed);
        if (sample.isEmpty() || GreedPouchItem.isGreedPouch(sample) || sample.is(Items.AIR)) {
            greed.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_need_sample"), true);
            return false;
        }

        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(sample.getItem());
        if (rl == null) {
            greed.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_invalid_item"), true);
            return false;
        }
        String itemId = rl.toString();

        GreedComponent gc = GreedComponent.KEY.get(greed);
        Side side = gc.getCollectedTypeIds().contains(itemId) ? Side.SELL : Side.BUY;

        if (!GreedDealTracker.canDeal(level, itemId)) {
            greed.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_cap", itemId), true);
            return false;
        }

        int n = GreedDealTracker.getDealCount(level, itemId);
        int price = side == Side.SELL
                ? GreedDealTracker.sellPrice(n)
                : GreedDealTracker.buyPrice(n);

        // Soft pre-check (revalidated on commit)
        if (side == Side.SELL) {
            // partner pays greed
            if (shopBalance(partner) < price) {
                greed.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_greed.trade_partner_broke", price), true);
                return false;
            }
        } else {
            if (shopBalance(greed) < price) {
                greed.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_greed.trade_self_broke", price), true);
                return false;
            }
            if (countItem(partner, itemId) < 1) {
                greed.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_greed.trade_partner_no_item"), true);
                return false;
            }
        }

        return openSession(level, greed, partner, side, itemId, price);
    }

    private static boolean openSession(ServerLevel level, ServerPlayer greed, ServerPlayer partner,
                                       Side side, String itemId, int price) {
        // Cancel any existing sessions for either player
        cancelPlayerSession(level.getServer(), greed.getUUID(), "replaced");
        cancelPlayerSession(level.getServer(), partner.getUUID(), "replaced");

        UUID sid = UUID.randomUUID();
        Session session = new Session(
                sid,
                greed.getUUID(),
                partner.getUUID(),
                side,
                itemId,
                price,
                level.getGameTime(),
                level.dimension().location()
        );
        SESSIONS.put(sid, session);
        PLAYER_SESSION.put(greed.getUUID(), sid);
        PLAYER_SESSION.put(partner.getUUID(), sid);

        String sideKey = side == Side.SELL ? "sell" : "buy";
        Component itemName = itemDisplayName(itemId);

        // Anonymous to partner: do not reveal greed identity in copy; use generic label
        greed.displayClientMessage(
                Component.translatable(
                        "message.habitrain_core.sin_greed.trade_offer_self",
                        sideKey, itemName, price, partner.getGameProfile().getName()
                ),
                false
        );
        partner.displayClientMessage(
                Component.translatable(
                        "message.habitrain_core.sin_greed.trade_offer_partner",
                        sideKey, itemName, price
                ),
                false
        );

        sendConfirmButtons(greed, sid);
        sendConfirmButtons(partner, sid);

        // Optional S2C prompt for future GUI clients
        try {
            GreedTradePromptPayload prompt = new GreedTradePromptPayload(
                    sid.toString(),
                    side.name(),
                    itemId,
                    price,
                    side == Side.SELL ? partner.getGameProfile().getName() : "???"
            );
            ServerPlayNetworking.send(greed, prompt);
            ServerPlayNetworking.send(partner, prompt);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.debug("[GreedTrade] S2C prompt skipped", t);
        }

        HabiTrainCore.LOGGER.info("[GreedTrade] open {} {} {} price={} greed={} partner={}",
                sid, side, itemId, price,
                greed.getGameProfile().getName(), partner.getGameProfile().getName());
        return true;
    }

    private static void sendConfirmButtons(ServerPlayer player, UUID sessionId) {
        String id = sessionId.toString();
        MutableComponent confirm = Component.translatable("message.habitrain_core.sin_greed.trade_btn_confirm")
                .withStyle(Style.EMPTY
                        .withColor(0x55FF55)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND,
                                "/habi_api greed_trade confirm " + id))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("message.habitrain_core.sin_greed.trade_btn_confirm_tip"))));
        MutableComponent cancel = Component.translatable("message.habitrain_core.sin_greed.trade_btn_cancel")
                .withStyle(Style.EMPTY
                        .withColor(0xFF5555)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND,
                                "/habi_api greed_trade cancel " + id))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("message.habitrain_core.sin_greed.trade_btn_cancel_tip"))));
        player.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_greed.trade_buttons", confirm, cancel),
                false
        );
    }

    public static void confirm(ServerPlayer player, String sessionIdRaw) {
        Session s = findOpen(sessionIdRaw);
        if (s == null) {
            player.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_gone"), true);
            return;
        }
        UUID u = player.getUUID();
        if (!u.equals(s.greedUuid) && !u.equals(s.partnerUuid)) {
            player.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_not_party"), true);
            return;
        }
        if (u.equals(s.greedUuid)) s.greedConfirmed = true;
        if (u.equals(s.partnerUuid)) s.partnerConfirmed = true;

        player.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_confirmed"), true);

        if (s.greedConfirmed && s.partnerConfirmed) {
            tryCommit(player.serverLevel().getServer(), s);
        } else {
            ServerPlayer other = otherParty(player.serverLevel().getServer(), s, u);
            if (other != null) {
                other.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_greed.trade_wait_other"),
                        true
                );
            }
        }
    }

    public static void cancel(ServerPlayer player, String sessionIdRaw) {
        Session s = findOpen(sessionIdRaw);
        if (s == null) {
            player.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_gone"), true);
            return;
        }
        UUID u = player.getUUID();
        if (!u.equals(s.greedUuid) && !u.equals(s.partnerUuid)) {
            player.displayClientMessage(Component.translatable("message.habitrain_core.sin_greed.trade_not_party"), true);
            return;
        }
        cancelSession(player.serverLevel().getServer(), s, "cancel");
    }

    private static void tryCommit(MinecraftServer server, Session s) {
        if (s.closed) return;
        ServerLevel level = resolveLevel(server, s);
        if (level == null) {
            forceClose(s);
            return;
        }
        ServerPlayer greed = level.getServer().getPlayerList().getPlayer(s.greedUuid);
        ServerPlayer partner = level.getServer().getPlayerList().getPlayer(s.partnerUuid);
        if (greed == null || partner == null) {
            notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_offline"));
            forceClose(s);
            return;
        }
        if (greed.isSpectator() || partner.isSpectator() || !greed.isAlive() || !partner.isAlive()) {
            notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_fail_state"));
            forceClose(s);
            return;
        }
        if (!isGreedRole(level, greed)) {
            notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_not_greed"));
            forceClose(s);
            return;
        }
        // Critical: pouch still owned by greed — never strip pouch via trade
        if (!GreedPouchItem.playerHasOwnPouch(greed)) {
            notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_no_pouch"));
            forceClose(s);
            return;
        }
        if (!GreedDealTracker.canDeal(level, s.itemId)) {
            notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_cap", s.itemId));
            forceClose(s);
            return;
        }

        // Fail closed if price tier drifted since session open
        int n = GreedDealTracker.getDealCount(level, s.itemId);
        int price = s.side == Side.SELL
                ? GreedDealTracker.sellPrice(n)
                : GreedDealTracker.buyPrice(n);
        if (price != s.price) {
            HabiTrainCore.LOGGER.info("[GreedTrade] price drift {} -> {} for {} — cancel", s.price, price, s.itemId);
            cancelSession(server, s, "price_changed");
            return;
        }

        GreedComponent gc = GreedComponent.KEY.get(greed);
        Item item = resolveItem(s.itemId);
        if (item == null || item == Items.AIR) {
            notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_invalid_item"));
            forceClose(s);
            return;
        }

        try {
            if (s.side == Side.SELL) {
                if (!gc.getCollectedTypeIds().contains(s.itemId)) {
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_no_collect"));
                    forceClose(s);
                    return;
                }
                if (shopBalance(partner) < price) {
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_partner_broke", price));
                    forceClose(s);
                    return;
                }
                // Transfer coins partner → greed
                if (!transferCoins(partner, greed, price)) {
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_coin_fail"));
                    forceClose(s);
                    return;
                }
                // Remove type from collection; give partner 1 stack (never pouch)
                if (!gc.removeCollectedType(s.itemId)) {
                    // refund
                    transferCoins(greed, partner, price);
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_no_collect"));
                    forceClose(s);
                    return;
                }
                ItemStack give = new ItemStack(item, 1);
                if (GreedPouchItem.isGreedPouch(give)) {
                    // safety: never give pouch
                    gc.addCollectedTypeSilent(s.itemId);
                    transferCoins(greed, partner, price);
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_invalid_item"));
                    forceClose(s);
                    return;
                }
                if (!partner.getInventory().add(give)) {
                    partner.drop(give, false);
                }
            } else { // BUY
                // Gate before taking item/coins: must still need a new type
                if (gc.isCollectionComplete() || gc.getCollectedTypeIds().contains(s.itemId)) {
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_no_collect"));
                    forceClose(s);
                    return;
                }
                if (shopBalance(greed) < price) {
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_self_broke", price));
                    forceClose(s);
                    return;
                }
                if (countItem(partner, s.itemId) < 1) {
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_partner_no_item"));
                    forceClose(s);
                    return;
                }
                // Take item first, then coins, then collection
                ItemStack taken = takeOne(partner, s.itemId);
                if (taken.isEmpty() || GreedPouchItem.isGreedPouch(taken)) {
                    if (!taken.isEmpty() && GreedPouchItem.isGreedPouch(taken)) {
                        // restore pouch if somehow matched — should not happen
                        partner.getInventory().add(taken);
                    }
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_partner_no_item"));
                    forceClose(s);
                    return;
                }
                if (!transferCoins(greed, partner, price)) {
                    // refund item
                    if (!partner.getInventory().add(taken)) {
                        partner.drop(taken, false);
                    }
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_coin_fail"));
                    forceClose(s);
                    return;
                }
                // Consume taken stack into virtual collection only if type is newly added
                boolean fresh = gc.addCollectedType(greed, s.itemId, taken.getHoverName());
                if (!fresh) {
                    // collection complete / already had type — refund coins + item, no deal
                    transferCoins(partner, greed, price);
                    if (!partner.getInventory().add(taken)) {
                        partner.drop(taken, false);
                    }
                    notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_no_collect"));
                    forceClose(s);
                    return;
                }
                taken.setCount(0);
            }

            GreedDealTracker.recordDeal(level, s.itemId);
            // Re-assert pouch still present after transfers
            if (!GreedPouchItem.playerHasOwnPouch(greed)) {
                HabiTrainCore.LOGGER.error("[GreedTrade] pouch missing after commit for {} — trade must never strip pouch",
                        greed.getGameProfile().getName());
            }

            Component itemName = itemDisplayName(s.itemId);
            notifyBoth(server, s, Component.translatable(
                    "message.habitrain_core.sin_greed.trade_success",
                    s.side.name().toLowerCase(), itemName, price
            ));
            HabiTrainCore.LOGGER.info("[GreedTrade] commit {} {} {} price={} n={}",
                    s.id, s.side, s.itemId, price, GreedDealTracker.getDealCount(level, s.itemId));
            forceClose(s);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[GreedTrade] commit failed", t);
            notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_fail_state"));
            forceClose(s);
        }
    }

    private static boolean transferCoins(ServerPlayer from, ServerPlayer to, int amount) {
        if (amount <= 0) return true;
        try {
            SREPlayerShopComponent fromShop = SREPlayerShopComponent.KEY.get(from);
            SREPlayerShopComponent toShop = SREPlayerShopComponent.KEY.get(to);
            if (fromShop == null || toShop == null) return false;
            if (fromShop.balance < amount) return false;
            fromShop.setBalance(fromShop.balance - amount);
            toShop.addToBalance(amount);
            return true;
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[GreedTrade] coin transfer failed", t);
            return false;
        }
    }

    private static int shopBalance(ServerPlayer player) {
        try {
            SREPlayerShopComponent shop = SREPlayerShopComponent.KEY.get(player);
            return shop != null ? shop.balance : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int countItem(ServerPlayer player, String itemId) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.isEmpty() || GreedPouchItem.isGreedPouch(st)) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id != null && itemId.equals(id.toString())) {
                total += st.getCount();
            }
        }
        return total;
    }

    private static ItemStack takeOne(ServerPlayer player, String itemId) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.isEmpty() || GreedPouchItem.isGreedPouch(st)) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id != null && itemId.equals(id.toString())) {
                ItemStack taken = st.split(1);
                if (st.isEmpty()) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    private static @Nullable Item resolveItem(String itemId) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) return null;
            return BuiltInRegistries.ITEM.get(rl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Component itemDisplayName(String itemId) {
        Item item = resolveItem(itemId);
        if (item != null && item != Items.AIR) {
            return new ItemStack(item).getHoverName();
        }
        return Component.literal(itemId);
    }

    private static ItemStack resolveSample(ServerPlayer greed) {
        ItemStack main = greed.getMainHandItem();
        ItemStack off = greed.getOffhandItem();
        if (GreedPouchItem.isGreedPouch(main) && !off.isEmpty() && !GreedPouchItem.isGreedPouch(off)) {
            return off;
        }
        if (GreedPouchItem.isGreedPouch(off) && !main.isEmpty() && !GreedPouchItem.isGreedPouch(main)) {
            return main;
        }
        // Prefer non-pouch non-empty
        if (!main.isEmpty() && !GreedPouchItem.isGreedPouch(main)) return main;
        if (!off.isEmpty() && !GreedPouchItem.isGreedPouch(off)) return off;
        return ItemStack.EMPTY;
    }

    private static ServerPlayer resolveTarget(ServerPlayer self, @Nullable UUID targetId) {
        if (targetId != null) {
            Player p = self.level().getPlayerByUUID(targetId);
            if (p instanceof ServerPlayer sp && !sp.isSpectator()) return sp;
        }
        Vec3 eye = self.getEyePosition();
        Vec3 look = self.getLookAngle();
        double range = TRADE_RANGE;
        AABB box = self.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        ServerPlayer best = null;
        double bestDot = 0.85;
        for (Player p : self.level().getEntitiesOfClass(Player.class, box)) {
            if (p == self || p.isSpectator()) continue;
            if (!(p instanceof ServerPlayer sp)) continue;
            Vec3 to = p.getEyePosition().subtract(eye);
            double dist = to.length();
            if (dist <= 0 || dist > range) continue;
            double angleDot = to.dot(look) / dist;
            if (angleDot > bestDot) {
                bestDot = angleDot;
                best = sp;
            }
        }
        return best;
    }

    private static boolean isGreedRole(ServerLevel level, ServerPlayer player) {
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game != null && SevenSins.GREED != null && game.isRole(player, SevenSins.GREED)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            return SevenSins.GREED_ID.equals(BlackoutRoleManager.getRoleHistory(level).get(player.getUUID()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static @Nullable Session findOpen(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            UUID id = UUID.fromString(raw.trim());
            Session s = SESSIONS.get(id);
            if (s == null || s.closed) return null;
            return s;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void cancelPlayerSession(MinecraftServer server, UUID player, String reason) {
        UUID sid = PLAYER_SESSION.get(player);
        if (sid == null) return;
        Session s = SESSIONS.get(sid);
        if (s != null && !s.closed) {
            cancelSession(server, s, reason);
        }
    }

    private static void cancelSession(MinecraftServer server, Session s, String reason) {
        if (s.closed) return;
        notifyBoth(server, s, Component.translatable("message.habitrain_core.sin_greed.trade_cancelled", reason));
        forceClose(s);
        HabiTrainCore.LOGGER.info("[GreedTrade] cancel {} reason={}", s.id, reason);
    }

    private static void forceClose(Session s) {
        s.closed = true;
        SESSIONS.remove(s.id);
        PLAYER_SESSION.remove(s.greedUuid, s.id);
        PLAYER_SESSION.remove(s.partnerUuid, s.id);
    }

    private static void notifyBoth(MinecraftServer server, Session s, Component msg) {
        if (server == null) return;
        ServerPlayer a = server.getPlayerList().getPlayer(s.greedUuid);
        ServerPlayer b = server.getPlayerList().getPlayer(s.partnerUuid);
        if (a != null) a.displayClientMessage(msg, false);
        if (b != null) b.displayClientMessage(msg, false);
    }

    private static @Nullable ServerPlayer otherParty(MinecraftServer server, Session s, UUID self) {
        UUID other = self.equals(s.greedUuid) ? s.partnerUuid : s.greedUuid;
        return server.getPlayerList().getPlayer(other);
    }

    private static @Nullable ServerLevel resolveLevel(MinecraftServer server, Session s) {
        if (server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(s.levelDim)) {
                return level;
            }
        }
        return server.overworld();
    }
}
